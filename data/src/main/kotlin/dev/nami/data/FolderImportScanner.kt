package dev.nami.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class AudioGroup(
    val albumFolderName: String,
    val artistFolderName: String?,
    val artistDir: DocumentFile?,
    val audioFiles: List<DocumentFile>,
    val sourceDir: DocumentFile,
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "aiff", "webm",
    "wv", "ape", "tak", "dsf", "dff", "mod", "xm", "it", "s3m", "nsf", "spc", "vgm", "gbs",
)

private val COVER_STEMS = setOf("folder", "cover")
private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png")

private val DISC_FOLDER_NAME = Regex("^(disc|cd)\\s*\\d+$", RegexOption.IGNORE_CASE)

class FolderImportScanner @Inject constructor(@ApplicationContext private val context: Context) {

    fun scan(treeUri: Uri): List<AudioGroup> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return scanDirectory(root)
    }

    internal fun scanDirectory(root: DocumentFile): List<AudioGroup> {
        val children = root.listFiles()
        val rootAudio = children.filter { !it.isDirectory && isAudioFile(it) }
        val groups = mutableListOf<AudioGroup>()

        if (rootAudio.isNotEmpty()) {
            groups += AudioGroup(
                albumFolderName = root.name.orEmpty(),
                artistFolderName = null,
                artistDir = null,
                audioFiles = rootAudio,
                sourceDir = root,
            )
        }

        val dirs = children.filter { it.isDirectory }
        val (matchedDirs, otherDirs) = dirs.partition { DISC_FOLDER_NAME.matches(it.name.orEmpty()) }
        // ponytail: a single disc-like folder is a real album named "CD1", not a multi-disc release
        val discDirs = if (matchedDirs.size >= 2) matchedDirs else emptyList()
        val soloDirs = if (matchedDirs.size >= 2) otherDirs else dirs

        if (discDirs.isNotEmpty()) {
            val discAudio = discDirs.flatMap { collectAudioRecursively(it) }
            if (discAudio.isNotEmpty()) {
                groups += AudioGroup(
                    albumFolderName = root.name.orEmpty(),
                    artistFolderName = null,
                    artistDir = null,
                    audioFiles = discAudio,
                    sourceDir = root,
                )
            }
        }

        for (dir in soloDirs) {
            val nestedAudio = collectAudioRecursively(dir)
            if (nestedAudio.isNotEmpty()) {
                groups += AudioGroup(
                    albumFolderName = dir.name.orEmpty(),
                    artistFolderName = root.name,
                    artistDir = root,
                    audioFiles = nestedAudio,
                    sourceDir = dir,
                )
            }
        }

        return groups
    }

    private fun collectAudioRecursively(dir: DocumentFile): List<DocumentFile> {
        val children = dir.listFiles()
        val direct = children.filter { !it.isDirectory && isAudioFile(it) }
        val nested = children.filter { it.isDirectory }.flatMap { collectAudioRecursively(it) }
        return direct + nested
    }

    private fun isAudioFile(doc: DocumentFile): Boolean {
        val extension = doc.name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    /** Sibling .lrc next to an audio file (same folder, same basename) - picked up automatically
     * so a track imported from a folder that already has synced lyrics next to it doesn't need
     * the "Загрузить .lrc из файла" button at all. */
    fun findLyrics(doc: DocumentFile): DocumentFile? {
        val parent = doc.parentFile ?: return null
        val stem = doc.name?.substringBeforeLast('.', missingDelimiterValue = "") ?: return null
        if (stem.isEmpty()) return null
        return parent.listFiles().firstOrNull { candidate ->
            if (candidate.isDirectory) return@firstOrNull false
            val name = candidate.name ?: return@firstOrNull false
            name.substringBeforeLast('.', missingDelimiterValue = name).equals(stem, ignoreCase = true) &&
                name.substringAfterLast('.', missingDelimiterValue = "").equals("lrc", ignoreCase = true)
        }
    }

    /** Хвост группы C "CUE-поддержка" - .cue лежащий рядом с образом альбома (один большой файл
     * вместо отдельного файла на трек), тем же поиском по совпадающему имени, что и findLyrics. */
    fun findCue(doc: DocumentFile): DocumentFile? {
        val parent = doc.parentFile ?: return null
        val stem = doc.name?.substringBeforeLast('.', missingDelimiterValue = "") ?: return null
        if (stem.isEmpty()) return null
        return parent.listFiles().firstOrNull { candidate ->
            if (candidate.isDirectory) return@firstOrNull false
            val name = candidate.name ?: return@firstOrNull false
            name.substringBeforeLast('.', missingDelimiterValue = name).equals(stem, ignoreCase = true) &&
                name.substringAfterLast('.', missingDelimiterValue = "").equals("cue", ignoreCase = true)
        }
    }

    fun findFolderCover(dir: DocumentFile): DocumentFile? {
        return dir.listFiles().firstOrNull { doc ->
            if (doc.isDirectory) return@firstOrNull false
            val name = doc.name ?: return@firstOrNull false
            val stem = name.substringBeforeLast('.', missingDelimiterValue = name).lowercase()
            val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            stem in COVER_STEMS && extension in COVER_EXTENSIONS
        }
    }
}
