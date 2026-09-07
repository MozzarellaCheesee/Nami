package dev.nami.data

import android.util.Log
import dev.nami.domain.MusicBrainzCandidate
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** MusicBrainz's public search API (П.md §23.20's "автозаполнение из MusicBrainz") -- keyless,
 * like LRCLIB, but their usage policy requires a real identifying User-Agent (not the generic
 * one LrcLibClient/Stands4Client use) and a self-imposed ~1 request/second rate limit, enforced
 * here rather than left to the caller to remember. */
object MusicBrainzClient {
    private const val BASE_URL = "https://musicbrainz.org/ws/2/recording/"
    private const val TIMEOUT_MS = 8_000
    private const val MIN_INTERVAL_MS = 1100L

    @Volatile private var lastRequestAtMs = 0L

    fun search(title: String, artistName: String?): List<MusicBrainzCandidate> {
        throttle()
        val query = buildString {
            append("recording:\"${title.replace("\"", "")}\"")
            if (!artistName.isNullOrBlank()) append(" AND artist:\"${artistName.replace("\"", "")}\"")
        }
        val url = "$BASE_URL?query=${encode(query)}&fmt=json&limit=5"
        val body = httpGet(url) ?: return emptyList()
        return runCatching {
            val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
            (0 until recordings.length()).mapNotNull { i ->
                val rec = recordings.optJSONObject(i) ?: return@mapNotNull null
                val recTitle = rec.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = rec.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name")
                val release = rec.optJSONArray("releases")?.optJSONObject(0)
                val album = release?.optString("title")?.takeIf { it.isNotBlank() }
                val year = release?.optString("date")?.take(4)?.toIntOrNull()
                val genre = rec.optJSONArray("tags")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
                MusicBrainzCandidate(recTitle, artist, album, year, genre)
            }
        }.onFailure { e -> Log.w("MusicBrainzClient", "parse failed: ${e.message}") }.getOrDefault(emptyList())
    }

    private fun throttle() {
        val waitMs = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMs)
        if (waitMs > 0) Thread.sleep(waitMs)
        lastRequestAtMs = System.currentTimeMillis()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun httpGet(url: String): String? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                // MusicBrainz's API policy requires app name + version + contact -- a generic
                // User-Agent gets silently rate-limited or blocked.
                setRequestProperty("User-Agent", "Nami/1.0 (offline music player, no contact URL yet)")
                try {
                    if (responseCode !in 200..299) {
                        Log.w("MusicBrainzClient", "HTTP $responseCode for $url")
                        return null
                    }
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("MusicBrainzClient", "lookup failed: ${e.message}")
            null
        }
    }
}
