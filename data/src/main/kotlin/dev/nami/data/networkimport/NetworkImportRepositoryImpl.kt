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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                    NetworkImportSource.ARCHIVE -> searchArchive(trimmed)
                    NetworkImportSource.PIPED -> searchPiped(trimmed)
                }
            }.getOrElse {
                Log.w(TAG, "поиск в $source не удался", it)
                emptyList()
            }
        }

    override suspend fun importTrack(networkTrack: NetworkTrack): String? = withContext(Dispatchers.IO) {
        // У Piped прямой ссылки в результатах поиска нет - она короткоживущая, её добывают
        // отдельным запросом прямо перед скачиванием.
        val track = if (networkTrack.downloadUrl != null) networkTrack else resolvePiped(networkTrack)
            ?: return@withContext "Инстансы Piped сейчас не отдают этот трек - попробуй позже или другой источник"
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

    // ------------------------------------------------------------------ Internet Archive

    /**
     * Archive отдаёт не треки, а "предметы" (концерт, оцифрованная пластинка), внутри которых
     * лежат файлы. Поэтому два шага: сначала поиск предметов, потом их metadata с составом файлов -
     * зато на экране получается обычный плоский список треков, а не папки, по которым надо лазить.
     *
     * Предметов берём мало (ITEMS_PER_SEARCH), файлов из каждого - тоже: metadata большого
     * концерта весит сотни килобайт, а это мобильный трафик.
     */
    private suspend fun searchArchive(query: String): List<NetworkTrack> = coroutineScope {
        val url = "https://archive.org/advancedsearch.php?q=${encode("($query) AND mediatype:(audio)")}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=$ITEMS_PER_SEARCH&page=1&output=json"
        val body = httpGet(url) ?: return@coroutineScope emptyList()
        val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
            ?: return@coroutineScope emptyList()
        (0 until docs.length()).mapNotNull { i -> docs.optJSONObject(i) }
            .map { doc -> async { archiveItemTracks(doc) } }
            .awaitAll()
            .flatten()
    }

    private fun archiveItemTracks(doc: JSONObject): List<NetworkTrack> {
        val identifier = doc.optString("identifier").takeIf { it.isNotBlank() } ?: return emptyList()
        val itemTitle = doc.optString("title").takeIf { it.isNotBlank() } ?: identifier
        val creator = doc.optString("creator").takeIf { it.isNotBlank() }
        val meta = httpGet("https://archive.org/metadata/$identifier")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        val files = meta.optJSONArray("files") ?: return emptyList()
        // Один и тот же трек лежит в предмете сразу в нескольких форматах (flac + mp3 + ogg) -
        // группируем по имени без расширения и оставляем лучший, иначе список троится.
        val best = LinkedHashMap<String, Pair<JSONObject, Int>>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name").takeIf { it.isNotBlank() } ?: continue
            val rank = AUDIO_FORMAT_RANK[name.substringAfterLast('.', "").lowercase()] ?: continue
            val key = name.substringBeforeLast('.')
            val current = best[key]
            if (current == null || rank < current.second) best[key] = f to rank
        }
        return best.values.take(FILES_PER_ITEM).map { (f, _) ->
            val name = f.optString("name")
            val ext = name.substringAfterLast('.', "").uppercase()
            val sizeMb = f.optString("size").toLongOrNull()?.let { it / 1024 / 1024 }
            NetworkTrack(
                source = NetworkImportSource.ARCHIVE,
                id = "$identifier/$name",
                title = f.optString("title").takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                artistName = f.optString("artist").takeIf { it.isNotBlank() } ?: creator ?: itemTitle,
                durationSec = f.optString("length").toFloatOrNull()?.toInt(),
                artworkUrl = "https://archive.org/services/img/$identifier",
                detail = listOfNotNull(ext.takeIf { it.isNotEmpty() }, sizeMb?.let { "$it МБ" }).joinToString(" · "),
                downloadUrl = "https://archive.org/download/$identifier/${encodePath(name)}",
                fileName = name.substringAfterLast('/'),
            )
        }
    }

    // ------------------------------------------------------------------ Piped (YouTube)

    /**
     * Публичные инстансы Piped регулярно падают, переезжают и упираются в защиту YouTube от ботов,
     * поэтому хост не один, а список: первый ответивший осмысленным JSON становится рабочим до
     * конца сессии ([pipedInstance]), при следующей ошибке перебор начинается снова. Инстанс может
     * ответить 200 и телом {"error": "..."} - это тоже отказ, а не результат.
     */
    @Volatile
    private var pipedInstance: String? = null

    private fun searchPiped(query: String): List<NetworkTrack> {
        val body = pipedGet("/search?q=${encode(query)}&filter=music_songs") ?: return emptyList()
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            if (item.optString("type") != "stream") return@mapNotNull null
            // "/watch?v=ID" - собственный формат Piped, id отдельным полем нет.
            val videoId = item.optString("url").substringAfter("v=", "").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NetworkTrack(
                source = NetworkImportSource.PIPED,
                id = videoId,
                title = title,
                artistName = item.optString("uploaderName").takeIf { it.isNotBlank() },
                durationSec = item.optInt("duration").takeIf { it > 0 },
                artworkUrl = item.optString("thumbnail").takeIf { it.isNotBlank() },
                detail = null,
                // Ссылка на аудиопоток живёт минуты и привязана к инстансу - добываем её в момент
                // скачивания (см. resolvePiped), а не при показе результатов.
                downloadUrl = null,
                fileName = title,
            )
        }
    }

    /** Возвращает трек с проставленными downloadUrl/fileName или null, если ни один инстанс не
     * отдал потоки. Перекодирования нет намеренно: lossy → lossy только портит звук и время. */
    private fun resolvePiped(track: NetworkTrack): NetworkTrack? {
        val body = pipedGet("/streams/${track.id}") ?: return null
        val streams = JSONObject(body).optJSONArray("audioStreams") ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1
        var bestMime = ""
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val bitrate = s.optInt("bitrate")
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
                bestMime = s.optString("mimeType")
            }
        }
        val url = bestUrl ?: return null
        val ext = when {
            bestMime.contains("mp4") -> "m4a"
            bestMime.contains("webm") -> "webm"
            else -> "opus"
        }
        return track.copy(downloadUrl = url, fileName = "${track.title}.$ext")
    }

    /** Пробует запомненный инстанс, потом все остальные по порядку. */
    private fun pipedGet(path: String): String? {
        val hosts = listOfNotNull(pipedInstance) + PIPED_INSTANCES.filter { it != pipedInstance }
        for (host in hosts) {
            val body = httpGet("$host$path") ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (json.has("error")) {
                Log.w(TAG, "инстанс $host отказал: ${json.optString("error").take(120)}")
                continue
            }
            pipedInstance = host
            return body
        }
        pipedInstance = null
        return null
    }

    // ------------------------------------------------------------------ HTTP

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** URLEncoder кодирует пробел как "+", что верно для параметров запроса и неверно для пути -
     * archive.org по такой ссылке отдаёт 404. */
    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

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
        /** Не один захардкоженный хост: инстансы Piped то падают, то переезжают (их сообщество
         * само это признаёт), поэтому при отказе перебираем следующий. */
        val PIPED_INSTANCES = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.ducks.party",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.drgns.space",
            "https://pipedapi.r4fo.com",
            "https://pipedapi.nosebs.ru",
        )
        const val ITEMS_PER_SEARCH = 6
        const val FILES_PER_ITEM = 8
        /** Чем меньше число, тем предпочтительнее формат: сначала lossless, потом lossy. */
        val AUDIO_FORMAT_RANK = mapOf(
            "flac" to 0, "wav" to 1, "aiff" to 2, "aif" to 2, "ape" to 3, "shn" to 4,
            "m4a" to 5, "ogg" to 6, "opus" to 6, "mp3" to 7,
        )
    }
}
