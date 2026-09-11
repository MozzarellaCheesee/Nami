package dev.nami.data.spotify

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SpotifyApiClient {
    private const val BASE_URL = "https://api.spotify.com/v1"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val TIMEOUT_MS = 8_000

    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    fun parsePlaylistId(input: String): String? {
        if (input.startsWith("spotify:playlist:")) {
            return input.substringAfter("spotify:playlist:")
        }
        if (input.contains("open.spotify.com/playlist/")) {
            return input.substringAfter("playlist/").substringBefore("?")
        }
        if (input.length == 22 && input.matches(Regex("^[a-zA-Z0-9]+$"))) {
            return input
        }
        return null
    }

    private fun ensureToken(clientId: String, clientSecret: String): String? {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryTime) {
            return cachedToken
        }

        return try {
            (URL(TOKEN_URL).openConnection() as HttpURLConnection).run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                
                val auth = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $auth")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                
                outputStream.use { os ->
                    os.write("grant_type=client_credentials".toByteArray())
                }
                
                try {
                    if (responseCode !in 200..299) return null
                    val jsonStr = inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val token = json.getString("access_token")
                    val expiresIn = json.getLong("expires_in")
                    
                    cachedToken = token
                    tokenExpiryTime = System.currentTimeMillis() + (expiresIn - 60) * 1000
                    token
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("SpotifyApiClient", "Token fetch failed: ${e.message}")
            null
        }
    }

    private fun httpGet(urlStr: String, token: String): String? {
        return try {
            (URL(urlStr).openConnection() as HttpURLConnection).run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "Nami Android app")
                
                try {
                    if (responseCode !in 200..299) return null
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("SpotifyApiClient", "GET failed: ${e.message}")
            null
        }
    }

    fun fetchPlaylist(playlistId: String, clientId: String, clientSecret: String): SpotifyPlaylistInfo? {
        val token = ensureToken(clientId, clientSecret) ?: return null
        
        val infoStr = httpGet("$BASE_URL/playlists/$playlistId", token) ?: return null
        val infoJson = JSONObject(infoStr)
        val name = infoJson.optString("name", "")
        val description = infoJson.optString("description", "")
        val images = infoJson.optJSONArray("images")
        var imageUrl: String? = null
        if (images != null && images.length() > 0) {
            imageUrl = images.getJSONObject(0).optString("url")
        }
        val ownerName = infoJson.optJSONObject("owner")?.optString("display_name")
        val tracksObj = infoJson.optJSONObject("tracks")
        val totalTracks = tracksObj?.optInt("total", 0) ?: 0
        
        val tracks = mutableListOf<SpotifyTrackMeta>()
        var nextUrl = "$BASE_URL/playlists/$playlistId/tracks?limit=100&offset=0"
        
        while (true) {
            val tracksStr = httpGet(nextUrl, token) ?: break
            val tracksPageJson = JSONObject(tracksStr)
            val items = tracksPageJson.optJSONArray("items") ?: break
            
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val trackJson = item.optJSONObject("track") ?: continue
                if (trackJson.isNull("id")) continue
                
                tracks.add(parseTrack(trackJson))
            }
            
            nextUrl = tracksPageJson.optString("next", "")
            if (nextUrl.isEmpty() || nextUrl == "null") {
                break
            }
        }
        
        return SpotifyPlaylistInfo(
            id = playlistId,
            name = name,
            description = description.ifEmpty { null },
            imageUrl = imageUrl,
            ownerName = ownerName?.ifEmpty { null },
            totalTracks = totalTracks,
            tracks = tracks
        )
    }

    fun searchTrack(title: String, artist: String?, clientId: String, clientSecret: String): SpotifyTrackMeta? {
        val token = ensureToken(clientId, clientSecret) ?: return null
        
        var query = "track:${title}"
        if (artist != null) {
            query += " artist:${artist}"
        }
        
        val url = "$BASE_URL/search?type=track&limit=1&q=${encode(query)}"
        val response = httpGet(url, token) ?: return null
        
        val json = JSONObject(response)
        val tracks = json.optJSONObject("tracks")?.optJSONArray("items")
        if (tracks != null && tracks.length() > 0) {
            return parseTrack(tracks.getJSONObject(0))
        }
        return null
    }

    private fun parseTrack(trackJson: JSONObject): SpotifyTrackMeta {
        val spotifyId = trackJson.getString("id")
        val title = trackJson.getString("name")
        val durationMs = trackJson.optLong("duration_ms", 0L)
        val trackNo = trackJson.optInt("track_number", 1)
        val isrc = trackJson.optJSONObject("external_ids")?.optString("isrc")?.ifEmpty { null }
        
        val artistsList = mutableListOf<String>()
        val artistsArray = trackJson.optJSONArray("artists")
        if (artistsArray != null) {
            for (j in 0 until artistsArray.length()) {
                artistsList.add(artistsArray.getJSONObject(j).getString("name"))
            }
        }
        
        var albumName: String? = null
        var coverUrl: String? = null
        var year: String? = null
        
        val albumObj = trackJson.optJSONObject("album")
        if (albumObj != null) {
            albumName = albumObj.optString("name", "").ifEmpty { null }
            val releaseDate = albumObj.optString("release_date", "")
            if (releaseDate.isNotEmpty()) {
                year = releaseDate.substringBefore("-")
            }
            
            val images = albumObj.optJSONArray("images")
            if (images != null) {
                var bestUrl: String? = null
                for (j in 0 until images.length()) {
                    val img = images.getJSONObject(j)
                    val url = img.optString("url")
                    if (bestUrl == null) bestUrl = url
                    if (img.optInt("height") == 640) {
                        bestUrl = url
                        break
                    }
                }
                coverUrl = bestUrl
            }
        }
        
        return SpotifyTrackMeta(
            spotifyId = spotifyId,
            title = title,
            artists = artistsList,
            albumName = albumName,
            trackNo = trackNo,
            durationMs = durationMs,
            isrc = isrc,
            coverUrl = coverUrl,
            year = year
        )
    }
}
