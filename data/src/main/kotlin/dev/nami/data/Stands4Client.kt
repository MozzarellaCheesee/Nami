package dev.nami.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** STANDS4's Lyrics API (https://www.stands4.com/api.php) -- second lyrics source, only ever
 * queried when LRCLIB has nothing (see LyricsRepositoryImpl.fetchFromLrcLib), so the free
 * 100-requests/day quota lasts. Keyed: UID/TOKEN come from BuildConfig, populated at build time
 * from the STANDS4_UID/STANDS4_TOKEN environment variables (never committed -- see data's own
 * build.gradle.kts). Plain text only, no line timestamps -- STANDS4's API doesn't have them.
 *
 * Attribution: STANDS4's terms require crediting them and linking stands4.com wherever lyrics
 * from this source are shown -- see LyricsScreen's "Текст: STANDS4" credit line. */
object Stands4Client {
    private const val BASE_URL = "https://www.stands4.com/services/v2/lyrics.php"
    private const val TIMEOUT_MS = 8_000

    val isConfigured: Boolean
        get() = BuildConfig.STANDS4_UID.isNotBlank() && BuildConfig.STANDS4_TOKEN.isNotBlank()

    /** Returns plain lyrics text (no timestamps), or null on no match/not configured/any
     * network-parse failure. */
    fun findPlainLyrics(title: String, artistName: String?): String? {
        if (!isConfigured) return null
        val term = if (!artistName.isNullOrBlank()) "$artistName $title" else title
        val url = "$BASE_URL?uid=${BuildConfig.STANDS4_UID}&tokenid=${BuildConfig.STANDS4_TOKEN}" +
            "&term=${encode(term)}&format=json"
        val body = httpGet(url) ?: return null
        return runCatching {
            val root = JSONObject(body)
            val results = root.optJSONArray("result") ?: return null
            if (results.length() == 0) return null
            results.getJSONObject(0).optString("lyrics", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun httpGet(url: String): String? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Nami Android app")
                try {
                    if (responseCode !in 200..299) return null
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("Stands4Client", "lookup failed: ${e.message}")
            null
        }
    }
}
