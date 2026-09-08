package dev.nami.player

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ListenBrainzScrobbler"
private const val ENDPOINT = "https://api.listenbrainz.org/1/submit-listens"

/** П.md §23.23 "Скробблинг" - ListenBrainz только, свой user-токен (Настройки -> вставить токен,
 * SettingsRepository.listenBrainzToken), без Last.fm - тот требует зарегистрированное приложение
 * с api_key+api_secret, которых у проекта нет и заводить отдельное решение. Плейн HttpURLConnection,
 * как и остальные разовые HTTP-запросы в проекте (LRCLIB, локальная сеть) - целого HTTP-клиента
 * ради одного POST в 30 секунд не нужно. */
object ListenBrainzScrobbler {
    /** Синхронный блокирующий вызов - зовущий код (PlayerRepositoryImpl) сам оборачивает в
     * withContext(Dispatchers.IO)/scope.launch, здесь лишний диспетчер не нужен. Ошибки сети -
     * скробблинг это "было бы неплохо", не критичный путь, точка отправки просто пропускается,
     * никакой офлайн-очереди с ретраями (П.md её упоминает, но это отдельный объём работы). */
    fun submitListen(token: String, title: String, artist: String?, album: String?, listenedAtEpochSec: Long) {
        if (artist.isNullOrBlank()) return // ListenBrainz требует artist_name - без него весь listen отклоняется
        try {
            val metadata = JSONObject().apply {
                put("artist_name", artist)
                put("track_name", title)
                album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
            }
            val payload = JSONArray().put(JSONObject().put("listened_at", listenedAtEpochSec).put("track_metadata", metadata))
            val body = JSONObject().put("listen_type", "single").put("payload", payload).toString()

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Token $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "submit-listens вернул $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "submit-listens упал: ${e.message}")
        }
    }
}
