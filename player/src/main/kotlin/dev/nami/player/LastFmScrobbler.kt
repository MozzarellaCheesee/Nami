package dev.nami.player

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

private const val TAG = "LastFmScrobbler"
private const val LASTFM_API_URL = "https://ws.audioscrobbler.com/2.0/"

// Default Nami API key for open-source scrobbling
private const val DEFAULT_API_KEY = "c161947b19818818c32585f9cbbf06ee"
private const val DEFAULT_SECRET = "8ec92a472cbfab6f671ebfa8ea40bbd6"

/**
 * Last.fm Scrobbler supporting 2.0 API track.scrobble and track.updateNowPlaying.
 * Requires user's session key (sk), signs parameters with MD5 api_sig.
 */
object LastFmScrobbler {

    fun calculateSignature(params: Map<String, String>, secret: String): String {
        val sorted = params.entries
            .filter { it.key != "format" && it.key != "callback" }
            .sortedBy { it.key }
            .joinToString(separator = "") { "${it.key}${it.value}" } + secret
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(sorted.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun submitScrobble(
        sessionKey: String,
        title: String,
        artist: String?,
        album: String?,
        listenedAtEpochSec: Long,
        apiKey: String = DEFAULT_API_KEY,
        secret: String = DEFAULT_SECRET,
    ) {
        if (artist.isNullOrBlank() || sessionKey.isBlank()) return
        try {
            val params = mutableMapOf(
                "method" to "track.scrobble",
                "api_key" to apiKey,
                "sk" to sessionKey,
                "artist" to artist,
                "track" to title,
                "timestamp" to listenedAtEpochSec.toString(),
            )
            if (!album.isNullOrBlank()) {
                params["album"] = album
            }
            val sig = calculateSignature(params, secret)
            params["api_sig"] = sig
            params["format"] = "json"

            val postData = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            val conn = URL(LASTFM_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Last.fm scrobble failed HTTP $code: $err")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm submitScrobble error: ${e.message}")
        }
    }

    fun updateNowPlaying(
        sessionKey: String,
        title: String,
        artist: String?,
        album: String?,
        apiKey: String = DEFAULT_API_KEY,
        secret: String = DEFAULT_SECRET,
    ) {
        if (artist.isNullOrBlank() || sessionKey.isBlank()) return
        try {
            val params = mutableMapOf(
                "method" to "track.updateNowPlaying",
                "api_key" to apiKey,
                "sk" to sessionKey,
                "artist" to artist,
                "track" to title,
            )
            if (!album.isNullOrBlank()) {
                params["album"] = album
            }
            val sig = calculateSignature(params, secret)
            params["api_sig"] = sig
            params["format"] = "json"

            val postData = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            val conn = URL(LASTFM_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
            conn.responseCode // trigger request
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm updateNowPlaying error: ${e.message}")
        }
    }
}
