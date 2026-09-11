package dev.nami.data

import android.util.Log
import dev.nami.core.model.LyricLine
import dev.nami.core.model.Lyrics
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Клиент self-hosted сервера NAMI. Как [LrcLibClient] - `HttpURLConnection` + `org.json`:
 * отдельная сетевая библиотека ради десятка GET/POST этому проекту не нужна.
 *
 * TLS в локальной сети самоподписанный, поэтому доверие устанавливается ПИННИНГОМ отпечатка
 * сертификата (`fp=sha256:...` из QR-кода сопряжения), а не через центр сертификации.
 */
object NamiServerClient {
    private const val TAG = "NamiServerClient"
    private const val TIMEOUT_MS = 5_000

    /**
     * Куда и с чем ходить. `bases` - все известные адреса сервера (локальный, Tailscale,
     * внешний домен) в порядке предпочтения: дома сработает первый, в дороге - следующий.
     * `baseUrl` - рабочий на данный момент (для однократного запроса без перебора).
     */
    data class Config(
        val baseUrl: String,
        val token: String,
        val certSha256: String? = null,
        val bases: List<String> = listOf(baseUrl),
    )

    /** GET /api/health - жив ли сервер по этому адресу. */
    fun health(baseUrl: String, certSha256: String? = null): Boolean {
        val (code, _) = request("GET", "$baseUrl/api/health", null, null, certSha256) ?: return false
        return code == 200
    }

    /** Первый из адресов, отвечающий на `/api/health`. null - ни один не доступен. */
    fun reachableBase(candidates: List<String>, certSha256: String?): String? =
        candidates.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
            .firstOrNull { health(it, certSha256) }

    /**
     * POST /api/auth/qr/confirm - меняет challenge из QR на постоянный токен устройства.
     * Возвращает токен либо null.
     */
    fun confirmPairing(
        baseUrl: String,
        challenge: String,
        deviceName: String,
        certSha256: String?,
    ): String? {
        val body = JSONObject().put("challenge", challenge).put("device_name", deviceName).toString()
        val (code, text) = request("POST", "$baseUrl/api/auth/qr/confirm", body, null, certSha256)
            ?: return null
        if (code != 200) return null
        return runCatching { JSONObject(text).optString("token").ifBlank { null } }.getOrNull()
    }

    /**
     * POST /api/tracks/match - сопоставляет треки клиента с id библиотеки сервера по
     * (исполнитель, название, длительность ±2 с). Возвращает массив той же длины и
     * порядка: id сервера либо null. null весь ответ - сеть/ошибка.
     */
    fun matchTrackIds(cfg: Config, tracks: List<Triple<String?, String, Long>>): List<Long?>? {
        val arr = org.json.JSONArray()
        for ((artist, title, dur) in tracks) {
            val o = JSONObject().put("title", title).put("duration_ms", dur)
            if (!artist.isNullOrBlank()) o.put("artist", artist)
            arr.put(o)
        }
        val body = JSONObject().put("tracks", arr).toString()
        val (code, text) = request("POST", "${cfg.baseUrl}/api/tracks/match", body, cfg.token, cfg.certSha256)
            ?: return null
        if (code != 200) return null
        val res = runCatching { JSONObject(text).getJSONArray("matches") }.getOrNull() ?: return null
        return (0 until res.length()).map { if (res.isNull(it)) null else res.getLong(it) }
    }

    /** GET /api/tracks/{id}/waveform - JSON-массив из 120 значений RMS 0..1. */
    fun waveform(cfg: Config, serverTrackId: Long): List<Float>? {
        val (code, text) = request("GET", "${cfg.baseUrl}/api/tracks/$serverTrackId/waveform", null, cfg.token, cfg.certSha256)
            ?: return null
        if (code != 200) return null
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return null
        return (0 until arr.length()).map { arr.optDouble(it).toFloat() }
    }

    /** GET /api/tracks/{id} - метаданные трека, включая поля анализатора. */
    fun trackDetail(cfg: Config, serverTrackId: Long): JSONObject? {
        val (code, text) = request("GET", "${cfg.baseUrl}/api/tracks/$serverTrackId", null, cfg.token, cfg.certSha256)
            ?: return null
        if (code != 200) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** GET /api/tracks/{id}/lyrics - серверная лирика (поиск, кеш и перевод на сервере). */
    fun lyrics(cfg: Config, trackId: Long, translate: Boolean = false): Lyrics? {
        val url = "${cfg.baseUrl}/api/tracks/$trackId/lyrics" + if (translate) "?translate=1" else ""
        val (code, text) = request("GET", url, null, cfg.token, cfg.certSha256) ?: return null
        if (code != 200) return null
        return parseLyrics(text)
    }

    data class FriendNowPlaying(
        val userId: Long,
        val username: String,
        val trackId: Long,
        val title: String,
        val artist: String?,
        val positionMs: Long,
        val updatedAt: Long,
    )

    /** GET /api/now-playing - что слушают пользователи сервера прямо сейчас. */
    fun nowPlaying(cfg: Config): List<FriendNowPlaying>? {
        val (code, text) = request("GET", "${cfg.baseUrl}/api/now-playing", null, cfg.token, cfg.certSha256)
            ?: return null
        if (code != 200) return null
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return null
        val list = mutableListOf<FriendNowPlaying>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                FriendNowPlaying(
                    userId = o.optLong("user_id"),
                    username = o.optString("username"),
                    trackId = o.optLong("track_id"),
                    title = o.optString("title"),
                    artist = o.optString("artist").takeIf { it.isNotBlank() },
                    positionMs = o.optLong("position_ms"),
                    updatedAt = o.optLong("updated_at"),
                ),
            )
        }
        return list
    }


    /**
     * POST /api/auth/pair - обменивает восьмизначный код сопряжения (его показывает мастер
     * настройки рядом с QR) на постоянный токен устройства. Для ручного ввода в приложении,
     * когда камера не сработала.
     */
    fun pairWithCode(
        baseUrl: String,
        code: String,
        deviceName: String,
        certSha256: String?,
    ): String? {
        val body = JSONObject().put("code", code).put("device_name", deviceName).toString()
        val (c, text) = request("POST", "$baseUrl/api/auth/pair", body, null, certSha256) ?: return null
        if (c != 200) return null
        return runCatching { JSONObject(text).optString("token").ifBlank { null } }.getOrNull()
    }

    /**
     * Разбирает `nami://auth?challenge=...` или `nami://pair?code=...` (из QR мастера
     * настройки сервера), пробует адреса по очереди и обменивает на токен устройства.
     * Возвращает готовую конфигурацию или null.
     */
    fun pairFromAuthUri(uriString: String, deviceName: String): Config? {
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.scheme != "nami") return null

        // 1. Формат nami://pair?v=1&host=...&hosts=...&port=...&fp=...&code=...
        if (uri.host == "pair") {
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 4533
            val fp = uri.getQueryParameter("fp")?.removePrefix("sha256:")
            val code = uri.getQueryParameter("code") ?: return null
            val scheme = if (fp != null) "https" else "http"

            val hostParam = uri.getQueryParameter("host")
            val hostsParam = uri.getQueryParameter("hosts")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

            val allHosts = (listOfNotNull(hostParam) + hostsParam).distinct()
            // Отфильтровываем localhost и 127.0.0.1, если есть другие сетевые адреса
            val filteredHosts = if (allHosts.any { it != "localhost" && it != "127.0.0.1" }) {
                allHosts.filter { it != "localhost" && it != "127.0.0.1" }
            } else {
                allHosts
            }

            if (filteredHosts.isEmpty()) return null

            val ext = uri.getQueryParameter("ext")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?.let { if (it.startsWith("http")) it else "https://$it" }

            val bases = (listOfNotNull(ext) + filteredHosts.map { "$scheme://$it:$port" }).distinct()
            for (base in bases) {
                val certForBase = if (hostIsIpLiteral(base)) fp else null
                val token = pairWithCode(base, code, deviceName, certForBase) ?: continue
                return Config(base, token, certForBase, listOf(base) + bases.filter { it != base })
            }
            return null
        }

        // 2. Формат nami://auth?challenge=...&fp=sha256:...&port=...&hosts=ip1,ip2
        if (uri.host != "auth") return null
        val challenge = uri.getQueryParameter("challenge") ?: return null
        val fp = uri.getQueryParameter("fp")?.removePrefix("sha256:")
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 4533
        val hosts = uri.getQueryParameter("hosts")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val scheme = if (fp != null) "https" else "http"
        // ext - внешний адрес (Tailscale MagicDNS, домен) как есть, с настоящим сертификатом.
        val ext = uri.getQueryParameter("ext")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("http")) it else "https://$it" }
        val bases = (listOfNotNull(ext) + hosts.map { "$scheme://$it:$port" }).distinct()
        for (base in bases) {
            val certForBase = if (hostIsIpLiteral(base)) fp else null
            if (!health(base, certForBase)) continue
            val token = confirmPairing(base, challenge, deviceName, certForBase) ?: continue
            // Рабочий адрес - первым, остальные (Tailscale, домен) - как запасные в дорогу.
            return Config(base, token, certForBase, listOf(base) + bases.filter { it != base })
        }
        return null
    }

    /**
     * Отпечаток SHA-256 сертификата сервера - для ручного подключения по HTTPS без QR
     * (trust on first use): один запрос к `/api/health`, из рукопожатия берётся leaf-сертификат.
     * Null для `http://`, для доменных имён с публичным CA или при ошибке соединения.
     */
    fun fetchCertSha256(baseUrl: String): String? {
        if (!baseUrl.startsWith("https://")) return null
        if (!hostIsIpLiteral(baseUrl)) return null
        return runCatching {
            val captured = arrayOfNulls<String>(1)
            val conn = (URL("$baseUrl/api/health").openConnection() as HttpsURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                sslSocketFactory = capturingFactory(captured)
                setHostnameVerifier { _, _ -> true }
            }
            conn.responseCode
            conn.disconnect()
            captured[0]
        }.getOrNull()
    }

    private fun capturingFactory(out: Array<String?>): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                chain?.firstOrNull()?.let { cert ->
                    out[0] = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    /** GET по готовому URL лирики (`/api/lyrics?title=...` или `/api/tracks/{id}/lyrics`). */
    fun lyricsFromUrl(url: String, token: String, certSha256: String?): Lyrics? {
        val (code, text) = request("GET", url, null, token, certSha256) ?: return null
        if (code != 200) return null
        return parseLyrics(text)
    }

    /**
     * Разбор ответа ручки лирики - вынесен из сети, чтобы проверять без сервера.
     * Форма: `{ "lines": [ { "time_ms"?: Long, "text": String } ], ... }`.
     */
    fun parseLyrics(json: String): Lyrics? {
        val arr = runCatching { JSONObject(json) }.getOrNull()?.optJSONArray("lines") ?: return null
        val lines = ArrayList<LyricLine>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            val text = l.optString("text")
            if (text.isEmpty() && !l.has("time_ms")) continue
            lines.add(LyricLine(timeMs = if (l.has("time_ms")) l.optLong("time_ms") else 0L, text = text))
        }
        return if (lines.isEmpty()) null else Lyrics(lines)
    }

    /**
     * GET /api/tracks?limit={limit}&offset={offset} - список треков с сервера.
     * Возвращает JSONArray треков.
     */
    fun tracks(cfg: Config, limit: Int = 200, offset: Int = 0): org.json.JSONArray? {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return null
        val (code, body) = request("GET", "$base/api/tracks?limit=$limit&offset=$offset", null, cfg.token, cfg.certSha256) ?: return null
        return if (code == 200) runCatching { org.json.JSONArray(body) }.getOrNull() else null
    }

    /**
     * Скачать трек с сервера в файл. GET /api/tracks/{id}/stream/auto.
     * Возвращает true при успехе.
     */
    fun downloadTrack(cfg: Config, trackId: Long, destFile: java.io.File): Boolean {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return false
        return runCatching {
            val url = "$base/api/tracks/$trackId/stream/auto?token=${cfg.token}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                if (this is HttpsURLConnection && cfg.certSha256 != null && hostIsIpLiteral(url)) {
                    sslSocketFactory = pinnedFactory(cfg.certSha256)
                    setHostnameVerifier { _, _ -> true }
                }
            }
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            true
        }.onFailure { Log.w(TAG, "downloadTrack: ${it.message}") }.getOrElse { false }
    }

    /**
     * GET /api/sync?since={timestamp} - pull изменений с сервера.
     * Возвращает JSON: { "changes": [...], "current_ts": Long }.
     */
    fun syncPull(cfg: Config, since: Long): JSONObject? {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return null
        val (code, body) = request("GET", "$base/api/sync?since=$since", null, cfg.token, cfg.certSha256) ?: return null
        return if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null
    }

    /**
     * POST /api/sync - push изменений на сервер.
     * Body: { "changes": [...] }. Возвращает true при успехе.
     */
    fun syncPush(cfg: Config, changes: org.json.JSONArray): Boolean {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return false
        val body = JSONObject().put("changes", changes).toString()
        val (code, _) = request("POST", "$base/api/sync", body, cfg.token, cfg.certSha256) ?: return false
        return code == 200
    }

    /**
     * POST /api/scan - принудительный запуск сканирования библиотеки на сервере.
     */
    fun scanLibrary(cfg: Config): JSONObject? {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return null
        val (code, body) = request("POST", "$base/api/scan", "", cfg.token, cfg.certSha256) ?: return null
        return if (code in 200..299) runCatching { JSONObject(body) }.getOrNull() else null
    }

    /**
     * GET /api/library/health - отчёт о здоровье библиотеки сервера.
     */
    fun libraryHealth(cfg: Config): JSONObject? {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return null
        val (code, body) = request("GET", "$base/api/library/health", null, cfg.token, cfg.certSha256) ?: return null
        return if (code in 200..299) runCatching { JSONObject(body) }.getOrNull() else null
    }

    /**
     * POST /api/scrobble - отправка прослушанного трека на сервер NAMI.
     */
    fun scrobble(cfg: Config, trackId: Long, playedAt: Long = System.currentTimeMillis() / 1000): Boolean {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return false
        val body = JSONObject().apply {
            put("track_id", trackId)
            put("played_at", playedAt)
        }.toString()
        val (code, _) = request("POST", "$base/api/scrobble", body, cfg.token, cfg.certSha256) ?: return false
        return code in 200..299
    }

    /**
     * POST /api/tracks/upload?filename=<имя> - тело запроса = сам файл. Дедупликация на
     * сервере. Возвращает `{track_id, duplicate_of}` либо null.
     */
    fun uploadTrack(cfg: Config, file: java.io.File): JSONObject? {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return null
        val name = java.net.URLEncoder.encode(file.name, "UTF-8")
        val url = "$base/api/tracks/upload?filename=$name"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Authorization", "Bearer ${cfg.token}")
                setRequestProperty("Content-Type", "application/octet-stream")
                if (this is HttpsURLConnection && cfg.certSha256 != null && hostIsIpLiteral(url)) {
                    sslSocketFactory = pinnedFactory(cfg.certSha256)
                    setHostnameVerifier { _, _ -> true }
                }
            }
            file.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code in 200..299) runCatching { JSONObject(text) }.getOrNull() else null
        }.onFailure { Log.w(TAG, "uploadTrack: ${it.message}") }.getOrNull()
    }

    /**
     * POST /api/share - гостевая ссылка `{title, track_ids, ttl_secs, max_plays}`.
     * Возвращает полный URL ссылки (`<base>/share/<token>`) либо null.
     */
    fun createShare(
        cfg: Config,
        title: String,
        serverTrackIds: List<Long>,
        ttlSecs: Long?,
        maxPlays: Int?,
    ): String? {
        val base = reachableBase(cfg.bases, cfg.certSha256) ?: return null
        val body = JSONObject().apply {
            put("title", title)
            put("track_ids", org.json.JSONArray(serverTrackIds))
            if (ttlSecs != null) put("ttl_secs", ttlSecs)
            if (maxPlays != null) put("max_plays", maxPlays)
        }.toString()
        val (code, text) = request("POST", "$base/api/share", body, cfg.token, cfg.certSha256) ?: return null
        if (code != 200) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        o.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        return o.optString("token").takeIf { it.isNotBlank() }?.let { "$base/share/$it" }
    }

    // ---------------------------------------------------------------- HTTP

    private fun request(
        method: String,
        url: String,
        body: String?,
        bearer: String?,
        certSha256: String?,
    ): Pair<Int, String>? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Пиннинг отпечатка - только для адреса-IP в локальной сети (самоподписанный
            // сертификат). У внешнего домена настоящий сертификат от CA - обычная проверка.
            if (this is HttpsURLConnection && certSha256 != null && hostIsIpLiteral(url)) {
                sslSocketFactory = pinnedFactory(certSha256)
                setHostnameVerifier { _, _ -> true }
            }
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        code to text
    }.onFailure { Log.w(TAG, "$method $url: ${it.message}") }.getOrNull()

    fun hostIsIpLiteral(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull().orEmpty()
        return host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || host.contains(':')
    }

    /** SSLSocketFactory, доверяющий любому серверу с совпавшим SHA-256 сертификата. */
    private fun pinnedFactory(expected: String): SSLSocketFactory {
        val want = expected.removePrefix("sha256:").lowercase()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("сервер не прислал сертификат")
                val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (fp != want) throw CertificateException("отпечаток сертификата сервера не совпал")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }
}
