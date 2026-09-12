package dev.nami.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.TagDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.domain.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

private const val PREFS_NAME = "nami_settings"

/** П.md §2 "Полный экспорт в .zip". Собирает содержимое приватной папки с музыкой (аудио + все
 * lrc/перевод/romaji/word-timing sidecar-файлы уже лежат там же) и JSON-манифест с метаданными,
 * которых в файлах нет - треки из БД, плейлисты, теги, настройки. */
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val tagDao: TagDao,
) : BackupRepository {

    override suspend fun exportLibrary(
        destinationUri: String,
        onProgress: ((dev.nami.domain.BackupProgress) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val manifest = buildManifest()
            val resolver = context.contentResolver
            val rawTracks = trackDao.allRaw().filter { File(it.path).isFile }
            val files = rawTracks.flatMap { track ->
                val audio = File(track.path)
                val sidecars = audio.parentFile?.listFiles()?.filter {
                    it.isFile && it.nameWithoutExtension == audio.nameWithoutExtension &&
                        it.extension.lowercase() in setOf("lrc", "json", "txt")
                }.orEmpty()
                listOf(audio) + sidecars
            }.distinctBy { it.absolutePath }
            val totalCount = files.size + 1
            var currentIdx = 0

            onProgress?.invoke(dev.nami.domain.BackupProgress(currentIdx, totalCount, "manifest.json"))

            resolver.openOutputStream(android.net.Uri.parse(destinationUri))?.use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toString(2).toByteArray())
                    zip.closeEntry()
                    currentIdx++

                    val usedNames = HashSet<String>()
                    files.forEach { file ->
                        onProgress?.invoke(dev.nami.domain.BackupProgress(currentIdx, totalCount, file.name))
                        var entryName = file.name
                        var suffix = 1
                        while (!usedNames.add(entryName)) {
                            entryName = "${file.nameWithoutExtension}_${suffix++}.${file.extension}"
                        }
                        zip.putNextEntry(ZipEntry("music/$entryName"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        currentIdx++
                    }
                }
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun buildManifest(): JSONObject {
        val root = JSONObject()
        root.put("version", 1)

        val tracks = JSONArray()
        trackDao.allRaw().forEach { t ->
            tracks.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("path", t.path)
                    put("fileName", File(t.path).name)
                    put("rating", t.rating ?: JSONObject.NULL)
                    put("genre", t.genre ?: JSONObject.NULL)
                    put("note", t.note ?: JSONObject.NULL)
                    put("playCount", t.playCount)
                    put("firstPlayed", t.firstPlayed ?: JSONObject.NULL)
                    put("lastPlayed", t.lastPlayed ?: JSONObject.NULL)
                },
            )
        }
        root.put("tracks", tracks)

        val playlists = JSONArray()
        playlistDao.allRaw().forEach { p ->
            val entryIds = JSONArray()
            playlistTrackDao.tracksInPlaylist(p.id).forEach { entryIds.put(it.id) }
            playlists.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("isLiked", p.isLiked)
                    put("isSmart", p.isSmart)
                    put("smartQueryJson", p.smartQueryJson ?: JSONObject.NULL)
                    put("trackIds", entryIds)
                },
            )
        }
        root.put("playlists", playlists)

        val tags = JSONArray()
        val assignments = tagDao.allAssignmentsRaw().groupBy({ it.tagId }, { it.trackId })
        tagDao.allRaw().forEach { tag ->
            val trackIds = JSONArray()
            assignments[tag.id]?.forEach { trackIds.put(it) }
            tags.put(
                JSONObject().apply {
                    put("id", tag.id)
                    put("name", tag.name)
                    put("colorArgb", tag.colorArgb)
                    put("trackIds", trackIds)
                },
            )
        }
        root.put("tags", tags)

        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val settings = JSONObject()
        prefs.all.forEach { (key, value) -> settings.put(key, value ?: JSONObject.NULL) }
        root.put("settings", settings)

        return root
    }
}
