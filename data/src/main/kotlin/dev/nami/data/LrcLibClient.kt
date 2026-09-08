package dev.nami.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal client for lrclib.net's public API - no key, no account, exactly the kind of
 * network use План.md calls out as fine to do automatically (unlike telemetry/ads/accounts).
 * No HTTP library added for this: one GET + a JSON object is exactly what HttpURLConnection and
 * the SDK's bundled org.json already do. */
object LrcLibClient {
    private const val BASE_URL = "https://lrclib.net/api"
    private const val TIMEOUT_MS = 8_000

    /** Exact lookup first (fast, most likely to hit for a well-tagged file); search is the
     * fallback for anything that doesn't title/artist/duration-match precisely. Returns raw LRC
     * text (`syncedLyrics`) only - plain-only results aren't usable by the synced screen yet. */
    fun findSyncedLyrics(title: String, artistName: String?, durationMs: Long): String? {
        val durationSec = (durationMs / 1000).toInt()
        get(title, artistName, durationSec)?.let { return it }
        return search(title, artistName)
    }

    private fun get(title: String, artistName: String?, durationSec: Int): String? {
        val url = buildString {
            append("$BASE_URL/get?track_name=${encode(title)}")
            if (!artistName.isNullOrBlank()) append("&artist_name=${encode(artistName)}")
            if (durationSec > 0) append("&duration=$durationSec")
        }
        val body = httpGet(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()?.optSyncedLyrics()
    }

    private fun search(title: String, artistName: String?): String? {
        val url = buildString {
            append("$BASE_URL/search?track_name=${encode(title)}")
            if (!artistName.isNullOrBlank()) append("&artist_name=${encode(artistName)}")
        }
        val body = httpGet(url) ?: return null
        val results = runCatching { JSONArray(body) }.getOrNull() ?: return null
        for (i in 0 until results.length()) {
            val synced = results.optJSONObject(i)?.optSyncedLyrics()
            if (synced != null) return synced
        }
        return null
    }

    private fun JSONObject.optSyncedLyrics(): String? =
        if (optBoolean("instrumental", false)) null else optString("syncedLyrics", "").takeIf { it.isNotBlank() }

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
            Log.w("LrcLibClient", "lookup failed: ${e.message}")
            null
        }
    }
}
