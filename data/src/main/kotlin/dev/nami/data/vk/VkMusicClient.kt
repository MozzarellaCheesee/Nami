package dev.nami.data.vk

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP-клиент для работы с аудиозаписями VK.
 * Работает через стандартный [HttpURLConnection] без внешних зависимостей.
 * Требует [VkRateLimiter] перед каждым вызовом.
 */
object VkMusicClient {
    private const val TAG = "VkMusicClient"
    private const val API_URL = "https://api.vk.com/method"
    private const val API_VERSION = "5.131"
    private const val TIMEOUT_MS = 10_000
    private const val USER_AGENT = "KateMobileAndroid/56 lite-arm64-v8a (Android 14; SDK 34; arm64-v8a; Google Pixel 7; ru)"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Поиск аудиозаписей в VK Музыке.
     * @param query поисковый запрос (артист, название или артист + название)
     * @param token токен доступа с правами audio
     * @param count количество результатов (по умолчанию 30)
     */
    fun searchAudio(query: String, token: String, count: Int = 30): List<VkTrack> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || token.isBlank()) return emptyList()

        VkRateLimiter.throttle()

        val postParams = "q=${encode(trimmed)}&count=$count&auto_complete=1&v=$API_VERSION&access_token=${encode(token)}"
        val responseStr = httpPost("$API_URL/audio.search", postParams) ?: return emptyList()

        return try {
            val root = JSONObject(responseStr)
            if (root.has("error")) {
                val err = root.getJSONObject("error")
                Log.w(TAG, "VK API error ${err.optInt("error_code")}: ${err.optString("error_msg")}")
                return emptyList()
            }
            val responseObj = root.optJSONObject("response") ?: return emptyList()
            val items = responseObj.optJSONArray("items") ?: return emptyList()

            val results = ArrayList<VkTrack>(items.length())
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0L)
                val ownerId = item.optLong("owner_id", 0L)
                val title = item.optString("title", "")
                val artist = item.optString("artist", "")
                val duration = item.optInt("duration", 0)
                val url = item.optString("url", "").takeIf { it.isNotEmpty() }

                var coverUrl: String? = null
                val albumObj = item.optJSONObject("album")
                if (albumObj != null) {
                    val thumb = albumObj.optJSONObject("thumb")
                    if (thumb != null) {
                        coverUrl = thumb.optString("photo_300").takeIf { it.isNotEmpty() }
                            ?: thumb.optString("photo_600").takeIf { it.isNotEmpty() }
                            ?: thumb.optString("photo_68").takeIf { it.isNotEmpty() }
                    }
                }

                results.add(
                    VkTrack(
                        id = id,
                        ownerId = ownerId,
                        title = title,
                        artist = artist,
                        durationSec = duration,
                        url = url,
                        albumCoverUrl = coverUrl,
                        approxBitrateKbps = 320,
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка разбора ответа audio.search: ${e.message}")
            emptyList()
        }
    }

    /**
     * Быстрая проверка валидности токена доступа VK.
     */
    fun checkToken(token: String): Boolean {
        if (token.isBlank()) return false
        VkRateLimiter.throttle()
        val postParams = "count=1&q=test&v=$API_VERSION&access_token=${encode(token)}"
        val responseStr = httpPost("$API_URL/audio.search", postParams) ?: return false
        return try {
            val root = JSONObject(responseStr)
            !root.has("error") && root.has("response")
        } catch (_: Exception) {
            false
        }
    }

    private fun httpPost(urlStr: String, bodyParams: String): String? {
        return try {
            (URL(urlStr).openConnection() as HttpURLConnection).run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                outputStream.use { os ->
                    os.write(bodyParams.toByteArray(Charsets.UTF_8))
                }

                try {
                    if (responseCode !in 200..299) {
                        Log.w(TAG, "HTTP $responseCode from $urlStr")
                        return null
                    }
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST failed: ${e.message}")
            null
        }
    }
}
