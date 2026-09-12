package dev.nami.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Найденный сканером файл. Имя уже прочитано, поэтому обращение к нему бесплатно - в отличие от
 * `DocumentFile.getName()`, который уходит отдельным запросом к провайдеру на каждый вызов. */
data class ScannedFile(val uri: Uri, val name: String)

data class AudioGroup(
    val albumFolderName: String,
    val artistFolderName: String?,
    val audioFiles: List<ScannedFile>,
    /** Обложка папки альбома и фото папки артиста. Находятся тем же обходом каталога, что и сами
     * треки, поэтому отдельного листинга не требуют. Null, если подходящего изображения нет. */
    val cover: Uri? = null,
    val artistCover: Uri? = null,
    /** Соседние .lrc и .cue (тот же каталог, то же имя), по uri трека. */
    val lyricsByAudio: Map<Uri, Uri> = emptyMap(),
    val cueByAudio: Map<Uri, Uri> = emptyMap(),
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "aiff", "webm",
    "wv", "ape", "tak", "dsf", "dff", "mod", "xm", "it", "s3m", "nsf", "spc", "vgm", "gbs",
)

private val COVER_STEMS = setOf("folder", "cover")
private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png")

private val DISC_FOLDER_NAME = Regex("^(disc|cd)\\s*\\d+$", RegexOption.IGNORE_CASE)

class FolderImportScanner @Inject constructor(@ApplicationContext private val context: Context) {

    private class Child(val uri: Uri, val name: String, val isDirectory: Boolean)

    /** Аудиофайлы каталога вместе с найденными рядом .lrc/.cue. */
    private class Collected {
        val audio = mutableListOf<ScannedFile>()
        val lyrics = mutableMapOf<Uri, Uri>()
        val cue = mutableMapOf<Uri, Uri>()
    }

    fun scan(treeUri: Uri): List<AudioGroup> {
        val root = DocumentFile.fromTreeUri(context, treeUri)?.uri ?: return emptyList()
        return scanDirectory(root)
    }

    internal fun scanDirectory(root: Uri): List<AudioGroup> {
        val children = children(root)
        val rootName = displayName(root)
        val rootCover = coverOf(children)
        val groups = mutableListOf<AudioGroup>()

        val direct = Collected().apply { addDirect(children, this) }
        if (direct.audio.isNotEmpty()) {
            groups += AudioGroup(
                albumFolderName = rootName,
                artistFolderName = null,
                audioFiles = direct.audio,
                cover = rootCover,
                artistCover = null,
                lyricsByAudio = direct.lyrics,
                cueByAudio = direct.cue,
            )
        }

        val dirs = children.filter { it.isDirectory }
        val (matchedDirs, otherDirs) = dirs.partition { DISC_FOLDER_NAME.matches(it.name) }
        // ponytail: a single disc-like folder is a real album named "CD1", not a multi-disc release
        val discDirs = if (matchedDirs.size >= 2) matchedDirs else emptyList()
        val soloDirs = if (matchedDirs.size >= 2) otherDirs else dirs

        if (discDirs.isNotEmpty()) {
            val discs = Collected()
            for (dir in discDirs) collectRecursively(dir.uri, discs)
            if (discs.audio.isNotEmpty()) {
                groups += AudioGroup(
                    albumFolderName = rootName,
                    artistFolderName = null,
                    audioFiles = discs.audio,
                    cover = rootCover,
                    artistCover = null,
                    lyricsByAudio = discs.lyrics,
                    cueByAudio = discs.cue,
                )
            }
        }

        for (dir in soloDirs) {
            val dirChildren = children(dir.uri)
            val nested = Collected().apply { addDirect(dirChildren, this) }
            for (sub in dirChildren.filter { it.isDirectory }) collectRecursively(sub.uri, nested)
            if (nested.audio.isNotEmpty()) {
                groups += AudioGroup(
                    albumFolderName = dir.name,
                    artistFolderName = rootName,
                    audioFiles = nested.audio,
                    cover = coverOf(dirChildren),
                    artistCover = rootCover,
                    lyricsByAudio = nested.lyrics,
                    cueByAudio = nested.cue,
                )
            }
        }

        return groups
    }

    private fun collectRecursively(dir: Uri, into: Collected) {
        val children = children(dir)
        addDirect(children, into)
        for (sub in children.filter { it.isDirectory }) collectRecursively(sub.uri, into)
    }

    /** Аудиофайлы одного каталога плюс их .lrc/.cue. Соседи ищутся по уже вычитанному листингу,
     * а не повторным обходом каталога на каждый трек. */
    private fun addDirect(children: List<Child>, into: Collected) {
        val audio = children.filter { !it.isDirectory && extensionOf(it.name) in AUDIO_EXTENSIONS }
        if (audio.isEmpty()) return
        val filesByStem = children.filter { !it.isDirectory }.groupBy { stemOf(it.name).lowercase() }
        for (file in audio) {
            val siblings = filesByStem[stemOf(file.name).lowercase()].orEmpty()
            siblings.firstOrNull { extensionOf(it.name) == "lrc" }?.let { into.lyrics[file.uri] = it.uri }
            siblings.firstOrNull { extensionOf(it.name) == "cue" }?.let { into.cue[file.uri] = it.uri }
            into.audio += ScannedFile(file.uri, file.name)
        }
    }

    internal fun findFolderCover(dir: Uri): Uri? = coverOf(children(dir))

    private fun coverOf(children: List<Child>): Uri? = children.firstOrNull { child ->
        !child.isDirectory &&
            stemOf(child.name).lowercase() in COVER_STEMS &&
            extensionOf(child.name) in COVER_EXTENSIONS
    }?.uri

    private fun children(dir: Uri): List<Child> =
        if (dir.scheme == ContentResolver.SCHEME_CONTENT) queryChildren(dir) else fileChildren(dir)

    /** Один курсор на каталог, сразу с именем и MIME. `DocumentFile.listFiles()` возвращает только
     * идентификаторы, и каждое последующее обращение к `name`/`isDirectory` уходит отдельным
     * запросом к провайдеру: на библиотеке в тысячи файлов это минуты вместо секунд. */
    private fun queryChildren(dir: Uri): List<Child> {
        val docId = runCatching { DocumentsContract.getDocumentId(dir) }.getOrNull() ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(dir, docId)
        }.getOrNull() ?: return emptyList()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val out = ArrayList<Child>(cursor.count)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    out += Child(
                        uri = DocumentsContract.buildDocumentUriUsingTree(dir, id),
                        name = name,
                        isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
                out
            }
        }.getOrNull().orEmpty()
    }

    private fun fileChildren(dir: Uri): List<Child> {
        val path = dir.path ?: return emptyList()
        return File(path).listFiles().orEmpty().map { Child(Uri.fromFile(it), it.name, it.isDirectory) }
    }

    /** Имя самого каталога - единственное, чего нет в листинге родителя. Один запрос на сканирование. */
    private fun displayName(dir: Uri): String = if (dir.scheme == ContentResolver.SCHEME_CONTENT) {
        runCatching {
            context.contentResolver.query(
                dir,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull().orEmpty()
    } else {
        dir.path?.let { File(it).name }.orEmpty()
    }

    private fun stemOf(name: String): String = name.substringBeforeLast('.', missingDelimiterValue = name)

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
