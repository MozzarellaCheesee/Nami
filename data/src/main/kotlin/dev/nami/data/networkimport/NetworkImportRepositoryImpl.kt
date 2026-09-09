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
import dev.nami.domain.SettingsRepository
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
    private val settingsRepository: SettingsRepository,
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
                    NetworkImportSource.JAMENDO -> searchJamendo(trimmed)
                    NetworkImportSource.BANDCAMP -> searchBandcamp(trimmed)
                    NetworkImportSource.SOUNDCLOUD -> searchSoundCloud(trimmed)
                }
            }.getOrElse {
                Log.w(TAG, "поиск в $source не удался", it)
                emptyList()
            }
        }

    override suspend fun importTrack(networkTrack: NetworkTrack): String? = withContext(Dispatchers.IO) {
        // У части источников прямой ссылки в результатах поиска нет: у Piped она короткоживущая,
        // у Bandcamp/SoundCloud лежит на странице трека, а не в выдаче. Достаём перед скачиванием.
        val track = if (networkTrack.downloadUrl != null) {
            networkTrack
        } else {
            when (networkTrack.source) {
                NetworkImportSource.PIPED -> resolvePiped(networkTrack)
                NetworkImportSource.BANDCAMP -> resolveBandcamp(networkTrack)
                NetworkImportSource.SOUNDCLOUD -> resolveSoundCloud(networkTrack)
                else -> null
            } ?: return@withContext when (networkTrack.source) {
                NetworkImportSource.PIPED ->
                    "Инстансы Piped сейчас не отдают этот трек - попробуй позже или другой источник"
                NetworkImportSource.BANDCAMP ->
                    "Этот трек на Bandcamp не отдаётся бесплатно (или страница изменилась и её не удалось разобрать)"
                NetworkImportSource.SOUNDCLOUD ->
                    "Автор не разрешил скачивать этот трек - слушать его можно только на SoundCloud"
                else -> "Не удалось получить ссылку на файл"
            }
        }
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

    // ------------------------------------------------------------------ Jamendo

    /**
     * 350 000+ треков под Creative Commons, официальный версионированный REST API - самый чистый
     * источник из всех, наравне с Audius. client_id берётся из настроек, а не из константы: у
     * Jamendo ключ выдаётся на приложение, и один захардкоженный ключ означал бы, что трафик всех
     * установок NAMI идёт по чужой квоте (см. План-Импорт-из-сети-2.md).
     *
     * Ответ всегда обёрнут в headers/results, причём отказ (протухший или заблокированный ключ)
     * приходит с HTTP 200 и status=failed внутри - то есть проверять надо тело, а не код ответа.
     */
    private fun searchJamendo(query: String): List<NetworkTrack> {
        val clientId = settingsRepository.jamendoClientId.value?.trim().orEmpty()
        if (clientId.isEmpty()) return emptyList()
        val body = httpGet(
            "https://api.jamendo.com/v3.0/tracks/?client_id=${encode(clientId)}&format=json&limit=40" +
                "&audioformat=mp32&search=${encode(query)}",
        ) ?: return emptyList()
        val json = JSONObject(body)
        val status = json.optJSONObject("headers")?.optString("status")
        if (status != null && status != "success") {
            Log.w(TAG, "Jamendo отказал: ${json.optJSONObject("headers")?.optString("error_message")}")
            return emptyList()
        }
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val t = results.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // audiodownload - файл целиком, audio - потоковая версия. Первый есть не у всех треков
            // (артист мог запретить скачивание), тогда честнее взять поток, чем прятать трек.
            val download = t.optString("audiodownload").takeIf {
                it.isNotBlank() && t.optBoolean("audiodownload_allowed", true)
            } ?: t.optString("audio").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NetworkTrack(
                source = NetworkImportSource.JAMENDO,
                id = id,
                title = title,
                artistName = t.optString("artist_name").takeIf { it.isNotBlank() },
                durationSec = t.optInt("duration").takeIf { it > 0 },
                artworkUrl = t.optString("image").takeIf { it.isNotBlank() },
                detail = listOfNotNull(
                    t.optString("album_name").takeIf { it.isNotBlank() },
                    "CC",
                ).joinToString(" · "),
                downloadUrl = download,
                fileName = "$title.mp3",
            )
        }
    }

    // ------------------------------------------------------------------ SoundCloud

    /**
     * Регистрация приложений у SoundCloud закрыта, поэтому ключ - тот же, которым работает их
     * собственный веб-плеер, и берётся он из настроек: протухает он заметно чаще любого выданного
     * официально, а поле в настройках позволяет обновить его без нового APK.
     */
    private fun searchSoundCloud(query: String): List<NetworkTrack> {
        val clientId = settingsRepository.soundCloudClientId.value?.trim().orEmpty()
        if (clientId.isEmpty()) return emptyList()
        val body = httpGet(
            "https://api-v2.soundcloud.com/search/tracks?q=${encode(query)}&limit=40&client_id=${encode(clientId)}",
        ) ?: return emptyList()
        val items = JSONObject(body).optJSONArray("collection") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val t = items.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title = t.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NetworkTrack(
                source = NetworkImportSource.SOUNDCLOUD,
                id = id.toString(),
                title = title,
                artistName = t.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() },
                durationSec = (t.optLong("duration") / 1000).toInt().takeIf { it > 0 },
                artworkUrl = t.optString("artwork_url").takeIf { it.isNotBlank() },
                // Сразу видно, что качается, а что только слушается: у большинства треков автор
                // скачивание запрещает, и без подписи это выяснялось бы только по ошибке.
                detail = listOfNotNull(
                    t.optString("genre").takeIf { it.isNotBlank() },
                    if (t.optBoolean("downloadable")) "можно скачать" else "только прослушивание",
                ).joinToString(" · "),
                downloadUrl = null,
                fileName = "$title.mp3",
            )
        }
    }

    /**
     * Скачиваем только то, что автор пометил downloadable - иначе честная ошибка. HLS-поток в файл
     * не перекодируем: это уже не "открытый доступ", а обход технической защиты.
     *
     * Сначала пробуем их же ручку скачивания (она отдаёт исходник), но на анонимный client_id она
     * часто отвечает 401 - скачивание там для вошедших в аккаунт. Тогда берём progressive-версию:
     * это обычный mp3 одним файлом, тот же, что играет плеер на сайте.
     *
     * Ссылки короткоживущие и подписанные, поэтому карточка трека их не хранит - трек
     * перезапрашивается по id в момент скачивания.
     */
    private fun resolveSoundCloud(track: NetworkTrack): NetworkTrack? {
        val clientId = settingsRepository.soundCloudClientId.value?.trim().orEmpty()
        if (clientId.isEmpty()) return null
        val body = httpGet("https://api-v2.soundcloud.com/tracks/${track.id}?client_id=${encode(clientId)}")
            ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.optBoolean("downloadable")) return null
        if (json.optBoolean("has_downloads_left")) {
            httpGet("https://api-v2.soundcloud.com/tracks/${track.id}/download?client_id=${encode(clientId)}")
                ?.let { runCatching { JSONObject(it).optString("redirectUri") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?.let { return track.copy(downloadUrl = it) }
        }
        val transcodings = json.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            if (t.optJSONObject("format")?.optString("protocol") != "progressive") continue
            val streamUrl = t.optString("url").takeIf { it.isNotBlank() } ?: continue
            val resolved = httpGet("$streamUrl?client_id=${encode(clientId)}")
                ?.let { runCatching { JSONObject(it).optString("url") }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: continue
            return track.copy(downloadUrl = resolved)
        }
        return null
    }

    // ------------------------------------------------------------------ Bandcamp

    /**
     * Официального публичного API у Bandcamp нет: их Developer API - про продажи самого артиста,
     * а не про чужой каталог. Зато страница поиска bandcamp.com/search обычному HTTP-клиенту без
     * JS отдаёт заглушку "включите JavaScript" - разбирать её бессмысленно. Работает эндпоинт
     * автодополнения их же поиска (bcsearch_public_api), который отвечает обычным JSON: он и
     * используется вместо скрейпинга HTML-выдачи - на порядок надёжнее.
     *
     * Хрупкость всё равно осознанная: это не версионированный API, Bandcamp вправе поменять его
     * без предупреждения. Поэтому все разборы - через optString/opt* и runCatching, отказ читается
     * как "не нашлось/не разобрали", а не как краш.
     */
    private fun searchBandcamp(query: String): List<NetworkTrack> {
        val body = httpPostJson(
            "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic",
            JSONObject()
                .put("search_text", query)
                // "t" - только треки: альбом одной строкой в плоский список треков не ложится.
                .put("search_filter", "t")
                .put("full_page", false)
                .put("fan_id", JSONObject.NULL)
                .toString(),
        ) ?: return emptyList()
        val results = JSONObject(body).optJSONObject("auto")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            if (r.optString("type") != "t") return@mapNotNull null
            val url = r.optString("item_url_path").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = r.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NetworkTrack(
                source = NetworkImportSource.BANDCAMP,
                // Адрес страницы и есть идентификатор: по нему же потом достаётся ссылка на файл.
                id = url,
                title = title,
                artistName = r.optString("band_name").takeIf { it.isNotBlank() },
                durationSec = null,
                artworkUrl = r.optString("img").takeIf { it.isNotBlank() },
                detail = r.optString("album_name").takeIf { it.isNotBlank() },
                downloadUrl = null,
                fileName = "$title.mp3",
            )
        }
    }

    /**
     * Ссылка на файл лежит на странице трека в атрибуте data-tralbum (HTML-экранированный JSON).
     *
     * Скачиваем только то, что артист сам отдаёт бесплатно: минимальная цена 0 (name your price),
     * отдельная страница бесплатного скачивания или флаг на самом треке. Платный трек не
     * трогаем - это была бы уже не "открытая раздача", а обход платы.
     */
    private fun resolveBandcamp(track: NetworkTrack): NetworkTrack? {
        val html = httpGet(track.id) ?: return null
        val raw = Regex("data-tralbum=\"([^\"]*)\"").find(html)?.groupValues?.get(1) ?: return null
        val json = runCatching { JSONObject(unescapeHtml(raw)) }.getOrNull() ?: return null
        val info = json.optJSONArray("trackinfo")?.optJSONObject(0) ?: return null
        val freeByAlbum = json.optJSONObject("current")?.optDouble("minimum_price", -1.0) == 0.0 ||
            json.optString("freeDownloadPage").isNotBlank()
        val free = freeByAlbum || info.optBoolean("has_free_download") || info.optBoolean("free_album_download")
        if (!free) {
            Log.w(TAG, "трек Bandcamp платный, скачивание пропущено: ${track.id}")
            return null
        }
        val url = info.optJSONObject("file")?.optString("mp3-128")?.takeIf { it.isNotBlank() } ?: return null
        return track.copy(
            downloadUrl = url,
            durationSec = info.optDouble("duration", 0.0).toInt().takeIf { it > 0 },
        )
    }

    /** Атрибут в HTML экранирован, а полноценный HTML-парсер ради пяти сущностей тащить незачем. */
    private fun unescapeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    // ------------------------------------------------------------------ Piped (YouTube)

    /**
     * Публичные инстансы Piped регулярно падают, переезжают и упираются в защиту YouTube от ботов,
     * поэтому хост не один, а список: первый ответивший осмысленным JSON становится рабочим до
     * конца сессии, при следующей ошибке перебор начинается снова. Инстанс может ответить 200 и
     * телом {"error": "..."} - это тоже отказ, а не результат.
     *
     * Отдельная память под /search и /streams ([pipedSearchInstance]/[pipedStreamsInstance]),
     * а не один общий "рабочий инстанс" - у Piped это архитектурно разные пути: /search почти
     * всегда лёгкий и кешируемый, а /streams заставляет инстанс сходить в YouTube за потоком
     * заново, и именно там чаще всего прилетает "confirm you're not a bot". Инстанс, у которого
     * работает поиск, совсем не обязательно отдаёт потоки - общая память заставляла бы каждый раз
     * зря начинать перебор /streams с заведомо непригодного для этого хоста.
     */
    @Volatile
    private var pipedSearchInstance: String? = null
    @Volatile
    private var pipedStreamsInstance: String? = null

    private fun searchPiped(query: String): List<NetworkTrack> {
        val body = pipedGet("/search?q=${encode(query)}&filter=music_songs", forStreams = false) ?: return emptyList()
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
        val body = pipedGet("/streams/${track.id}", forStreams = true) ?: return null
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

    /** Пробует запомненный для этого вида запроса инстанс, потом остальные - в перемешанном
     * порядке, не в порядке списка. Один и тот же первый по списку хост иначе принимает весь
     * трафик всех установок NAMI разом, что и провоцирует его бот-чек чаще остальных - вразнобой
     * нагрузка размазывается по всему списку. */
    private fun pipedGet(path: String, forStreams: Boolean): String? {
        val sticky = if (forStreams) pipedStreamsInstance else pipedSearchInstance
        val hosts = listOfNotNull(sticky) + PIPED_INSTANCES.filter { it != sticky }.shuffled()
        for (host in hosts) {
            val body = httpGet("$host$path") ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (json.has("error")) {
                Log.w(TAG, "инстанс $host отказал: ${json.optString("error").take(120)}")
                continue
            }
            if (forStreams) pipedStreamsInstance = host else pipedSearchInstance = host
            return body
        }
        if (forStreams) pipedStreamsInstance = null else pipedSearchInstance = null
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

    /** Единственный POST на весь файл - поиск Bandcamp. Ради него отдельного слоя не заводим. */
    private fun httpPostJson(url: String, body: String): String? {
        val conn = openConnection(url, READ_TIMEOUT_MS) ?: return null
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "POST $url не удался: ${e.message}")
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
         * само это признаёт), поэтому при отказе перебираем следующий. Список сверен с
         * актуальным на момент правки https://github.com/TeamPiped/documentation (не с
         * piped-instances.kavin.rocks - тот сам нестабилен, 502 регулярно, незачем городить
         * лишнюю точку отказа поверх и так шаткого источника). */
        val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi.nosebs.ru",
            "https://pipedapi-libre.kavin.rocks",
            "https://piped-api.privacy.com.de",
            "https://pipedapi.adminforge.de",
            "https://api.piped.yt",
            "https://pipedapi.drgns.space",
            "https://pipedapi.owo.si",
            "https://pipedapi.ducks.party",
            "https://piped-api.codespace.cz",
            "https://pipedapi.reallyaweso.me",
            "https://api.piped.private.coffee",
            "https://pipedapi.darkness.services",
            "https://pipedapi.orangenet.cc",
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
