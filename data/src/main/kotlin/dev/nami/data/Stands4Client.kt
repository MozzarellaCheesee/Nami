package dev.nami.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** STANDS4's Lyrics API (https://www.stands4.com/api.php) - second lyrics source, only ever
 * queried when LRCLIB has nothing (see LyricsRepositoryImpl.fetchFromLrcLib). Keyed: uid/token
 * are each user's own (entered in Settings -> Лирика, stored via SettingsRepository), not a
 * shared key baked into the build - the free tier is 100 requests/day per account, and one
 * key shared across every install of this app would exhaust it immediately. Plain text only,
 * no line timestamps - STANDS4's API doesn't have them.
 *
 * Attribution: STANDS4's terms require crediting them and linking stands4.com wherever lyrics
 * from this source are shown - see LyricsScreen's "Текст: STANDS4" credit line. */
object Stands4Client {
    private const val BASE_URL = "https://www.stands4.com/services/v2/lyrics.php"
    private const val TIMEOUT_MS = 8_000

    /** Returns plain lyrics text (no timestamps), or null on no match/any network-parse failure.
     * Caller is responsible for checking uid/token are non-blank and the daily quota isn't spent
     * first (see LyricsRepositoryImpl) - this makes no assumption about either. */
    fun findPlainLyrics(title: String, artistName: String?, uid: String, token: String): String? {
        val term = if (!artistName.isNullOrBlank()) "$artistName $title" else title
        val url = "$BASE_URL?uid=${encode(uid)}&tokenid=${encode(token)}&term=${encode(term)}&format=json"
        val body = httpGet(url)
        if (body == null) {
            Log.w("Stands4Client", "request failed (network/HTTP error) for term=\"$term\"")
            return null
        }
        Log.d("Stands4Client", "response for term=\"$term\": $body")
        return runCatching {
            val root = JSONObject(body)
            // STANDS4's error responses come back 200 OK with an "ERRORS" object instead of
            // "result" - surfacing that message here is the only way to tell "wrong uid/token"
            // apart from "no match", both of which otherwise look identical (null result).
            root.optJSONObject("ERRORS")?.let { errors ->
                Log.w("Stands4Client", "API error for term=\"$term\": $errors")
                return null
            }
            val results = root.optJSONArray("result")
            if (results == null || results.length() == 0) {
                Log.d("Stands4Client", "no match for term=\"$term\"")
                return null
            }
            val lyrics = results.getJSONObject(0).optString("lyrics", "").takeIf { it.isNotBlank() }
            if (lyrics == null) Log.w("Stands4Client", "result had no non-blank \"lyrics\" field for term=\"$term\"")
            lyrics
        }.onFailure { e -> Log.w("Stands4Client", "parse failed for term=\"$term\": ${e.message}") }.getOrNull()
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
                    if (responseCode !in 200..299) {
                        Log.w("Stands4Client", "HTTP $responseCode for $url")
                        return null
                    }
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
