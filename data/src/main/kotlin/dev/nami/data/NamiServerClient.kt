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
    private const val TIMEOUT_MS = 10_000

    /** Куда и с чем ходить: адрес сервера, токен устройства, отпечаток сертификата. */
    data class Config(val baseUrl: String, val token: String, val certSha256: String? = null)

    /** GET /api/health - жив ли сервер по этому адресу. */
    fun health(baseUrl: String, certSha256: String? = null): Boolean {
        val (code, _) = request("GET", "$baseUrl/api/health", null, null, certSha256) ?: return false
        return code == 200
    }

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

    /** GET /api/tracks/{id}/lyrics - серверная лирика (поиск, кеш и перевод на сервере). */
    fun lyrics(cfg: Config, trackId: Long, translate: Boolean = false): Lyrics? {
        val url = "${cfg.baseUrl}/api/tracks/$trackId/lyrics" + if (translate) "?translate=1" else ""
        val (code, text) = request("GET", url, null, cfg.token, cfg.certSha256) ?: return null
        if (code != 200) return null
        return parseLyrics(text)
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
            if (this is HttpsURLConnection && certSha256 != null) {
                sslSocketFactory = pinnedFactory(certSha256)
                // Имя не проверяем: адрес - IP, а доверие даёт совпавший отпечаток.
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
