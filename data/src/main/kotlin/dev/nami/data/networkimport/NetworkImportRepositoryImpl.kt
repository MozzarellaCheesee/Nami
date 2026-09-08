package dev.nami.data.networkimport

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.NetworkImportRepository
import dev.nami.domain.NetworkImportSource
import dev.nami.domain.NetworkTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Поиск и скачивание треков из открытых сетевых источников (План-Импорт-из-сети.md).
 *
 * Своей HTTP-библиотеки тут нет намеренно: в проекте её нет вообще (LrcLibClient/MusicBrainzClient
 * ходят голым HttpURLConnection + org.json), а тащить Retrofit ради трёх GET-ов - лишняя зависимость.
 *
 * Скачанное не изобретает свой путь в библиотеку: файл кладётся во временную папку и уходит в тот
 * же LibraryRepository.import(ImportSource.Files(...)), что и выбор файла руками.
 */
@Singleton
class NetworkImportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
) : NetworkImportRepository {

    override suspend fun search(source: NetworkImportSource, query: String): List<NetworkTrack> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            runCatching {
                when (source) {
                    NetworkImportSource.AUDIUS -> searchAudius(trimmed)
                    NetworkImportSource.ARCHIVE -> emptyList()
                    NetworkImportSource.PIPED -> emptyList()
                }
            }.getOrElse {
                Log.w(TAG, "поиск в $source не удался", it)
                emptyList()
            }
        }

    override suspend fun importTrack(track: NetworkTrack): String? = withContext(Dispatchers.IO) {
        val url = track.downloadUrl ?: return@withContext "Не удалось получить ссылку на файл"
        // Уникальность даёт ПАПКА, а не префикс в имени: иначе uuid попадает в название трека у
        // файлов без тегов (тот же урок, что в Wi-Fi Drop).
        val scratchDir = File(File(context.cacheDir, "net_import"), UUID.randomUUID().toString())
        scratchDir.mkdirs()
        val file = File(scratchDir, track.fileName.take(120).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        try {
            if (!download(url, file)) return@withContext "Не получилось скачать файл"
            val before = libraryRepository.allTracksOrdered().map { it.id.value }.toSet()
            libraryRepository.import(ImportSource.Files(listOf(Uri.fromFile(file).toString()))).collect { }
            val imported = libraryRepository.allTracksOrdered().firstOrNull { it.id.value !in before }
                ?: return@withContext "Файл скачан, но библиотека его не приняла"
            // Теги берём из ответа API, а не гадаем заново: у скачанного mp3 их часто просто нет.
            libraryRepository.renameTrack(imported.id, track.title)
            track.artistName?.let {
                libraryRepository.batchEditTracks(listOf(imported.id), artistName = it, albumName = null, year = null, genre = null)
            }
            track.artworkUrl?.let { artUrl ->
                val art = File(scratchDir, "cover.jpg")
                if (download(artUrl, art)) {
                    runCatching { libraryRepository.setTrackCover(imported.id, Uri.fromFile(art).toString()) }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "импорт из сети не удался", e)
            "Ошибка: ${e.message ?: "не получилось"}"
        } finally {
            scratchDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ Audius

    /** Каталог Audius - инди/электроника, раздаётся самими артистами (Open Music License), ключи
     * и лимиты не нужны. api.audius.co сам по себе живой узел, а не только реестр узлов, так что
     * лишний запрос за списком нод не делаем - он же и есть балансировщик. */
    private fun searchAudius(query: String): List<NetworkTrack> {
        val body = httpGet("$AUDIUS_HOST/v1/tracks/search?query=${encode(query)}&limit=30&app_name=$APP_NAME")
            ?: return emptyList()
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            if (!t.optBoolean("is_streamable", true)) return@mapNotNull null
            val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = t.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NetworkTrack(
                source = NetworkImportSource.AUDIUS,
                id = id,
                title = title,
                artistName = t.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() },
                durationSec = t.optInt("duration").takeIf { it > 0 },
                artworkUrl = t.optJSONObject("artwork")?.let { it.optString("480x480").takeIf { u -> u.isNotBlank() } },
                detail = t.optString("genre").takeIf { it.isNotBlank() },
                downloadUrl = "$AUDIUS_HOST/v1/tracks/$id/stream?app_name=$APP_NAME",
                fileName = "$title.mp3",
            )
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun httpGet(url: String): String? = openConnection(url, READ_TIMEOUT_MS)?.let { conn ->
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url не удался: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, target: File): Boolean {
        val conn = openConnection(url, DOWNLOAD_TIMEOUT_MS) ?: return false
        return try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "скачивание $url не удалось: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String, readTimeoutMs: Int): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nami Android app")
        }
    } catch (e: Exception) {
        Log.w(TAG, "не открылось соединение с $url: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "NetworkImport"
        const val APP_NAME = "Nami"
        const val AUDIUS_HOST = "https://api.audius.co"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val DOWNLOAD_TIMEOUT_MS = 120_000
    }
}
