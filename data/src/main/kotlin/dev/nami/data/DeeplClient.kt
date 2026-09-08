package dev.nami.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** DeepL API - each user's own key (Settings -> Лирика), free-tier keys end in ":fx" and live on
 * api-free.deepl.com, paid keys on api.deepl.com. Noticeably better JA->RU than the on-device
 * MLKit model (the whole point of adding this as an alternative, not a replacement - MLKit stays
 * the offline/keyless fallback). One HTTP call for the whole batch of (already sentence-grouped,
 * see SentenceGrouper) lines - DeepL's own API accepts multiple `text` params per request. */
object DeeplClient {
    private const val TIMEOUT_MS = 10_000

    fun translate(texts: List<String>, apiKey: String, targetLang: String = "RU"): List<String>? {
        if (texts.isEmpty()) return emptyList()
        val host = if (apiKey.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = buildString {
            append("auth_key=").append(encode(apiKey))
            append("&target_lang=").append(encode(targetLang))
            texts.forEach { append("&text=").append(encode(it)) }
        }
        return try {
            (URL("https://$host/v2/translate").openConnection() as HttpURLConnection).run {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                try {
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    if (responseCode !in 200..299) {
                        Log.w("DeeplClient", "HTTP $responseCode")
                        return null
                    }
                    val raw = inputStream.bufferedReader().use { it.readText() }
                    val translations = JSONObject(raw).getJSONArray("translations")
                    (0 until translations.length()).map { translations.getJSONObject(it).getString("text") }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w("DeeplClient", "translate failed: ${e.message}")
            null
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
