package dev.nami.data

import dev.nami.domain.ServerAnalysis
import dev.nami.domain.ServerAudioRepository
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerAudioRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ServerAudioRepository {

    /** key = "artist|title|durSec" -> id сервера (или null - «искали, не нашли»). */
    private val idCache = ConcurrentHashMap<String, Long?>()

    /** Тот же ключ -> анализ трека с сервера. Чтобы параллельные проверки (ReplayGain и
     * BPM/тональность идут разными корутинами) не дёргали `/api/tracks/{id}` дважды. */
    private val analysisCache = ConcurrentHashMap<String, ServerAnalysis>()

    override fun isServerActive(): Boolean =
        settingsRepository.namiServerPreferred.value &&
            settingsRepository.namiServerToken.value != null &&
            settingsRepository.namiServerUrl.value.isNotBlank()

    /** Конфигурация с первым доступным адресом из списка; null - сервера нет/недоступен. */
    private fun activeConfig(): NamiServerClient.Config? {
        if (!isServerActive()) return null
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        val bases = settingsRepository.namiServerUrl.value.split('\n', ',')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val base = NamiServerClient.reachableBase(bases, cert) ?: return null
        if (base != bases.firstOrNull()) {
            settingsRepository.setNamiServerUrl((listOf(base) + bases.filter { it != base }).joinToString("\n"))
        }
        return NamiServerClient.Config(base, token, cert, listOf(base) + bases.filter { it != base })
    }

    private fun cacheKey(artist: String?, title: String, durationMs: Long) =
        "${artist?.trim()?.lowercase().orEmpty()}|${title.trim().lowercase()}|${durationMs / 1000}"

    override suspend fun serverTrackId(artist: String?, title: String, durationMs: Long): Long? {
        val key = cacheKey(artist, title, durationMs)
        idCache[key]?.let { return it }
        if (idCache.containsKey(key)) return null // уже искали, не нашли
        return withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val id = NamiServerClient.matchTrackIds(cfg, listOf(Triple(artist, title, durationMs)))
                ?.firstOrNull()
            idCache[key] = id
            id
        }
    }

    override suspend fun serverAnalysis(artist: String?, title: String, durationMs: Long): ServerAnalysis? {
        val key = cacheKey(artist, title, durationMs)
        analysisCache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val id = serverTrackId(artist, title, durationMs) ?: return@withContext null
            val obj = NamiServerClient.trackDetail(cfg, id) ?: return@withContext null
            parseAnalysis(obj)?.also { analysisCache[key] = it }
        }
    }

    override fun serverStreamUrl(serverTrackId: Long): String? {
        val cfg = activeConfig() ?: return null
        return streamUrlOn(cfg.baseUrl, cfg.token, serverTrackId)
    }

    /** URL потока, но только если адрес пригоден для ExoPlayer: `http://` или реальный
     * домен. Для `https://<IP>` (самоподписанный) - null, там нужен свой датасорс. */
    private fun streamUrlOn(base: String, token: String, id: Long): String? {
        val host = runCatching { java.net.URL(base).host }.getOrNull().orEmpty()
        val isIp = host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || host.contains(':')
        if (base.startsWith("https://") && isIp) return null
        return "$base/api/tracks/$id/stream/auto?token=$token"
    }

    override suspend fun serverStreamUrls(tracks: List<Triple<String?, String, Long>>): List<String?> =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext List(tracks.size) { null }
            val ids = NamiServerClient.matchTrackIds(cfg, tracks)
                ?: return@withContext List(tracks.size) { null }
            ids.forEachIndexed { i, id ->
                if (i < tracks.size) {
                    val (artist, title, dur) = tracks[i]
                    idCache[cacheKey(artist, title, dur)] = id
                }
            }
            ids.map { id -> id?.let { streamUrlOn(cfg.baseUrl, cfg.token, it) } }
        }

    companion object {
        /** Разбор полей анализа из ответа `GET /api/tracks/{id}` - отдельно, чтобы тестировать. */
        fun parseAnalysis(o: JSONObject): ServerAnalysis? {
            fun f(name: String): Float? = if (o.has(name) && !o.isNull(name)) o.optDouble(name).toFloat() else null
            fun s(name: String): String? = if (o.has(name) && !o.isNull(name)) o.optString(name).ifBlank { null } else null
            val a = ServerAnalysis(
                replayGainTrackGainDb = f("replaygain_track_gain"),
                replayGainTrackPeak = f("replaygain_track_peak"),
                r128LoudnessLufs = f("r128_loudness"),
                bpm = f("bpm"),
                musicalKey = s("musical_key"),
            )
            return if (a.replayGainTrackGainDb == null && a.r128LoudnessLufs == null &&
                a.bpm == null && a.musicalKey == null
            ) null else a
        }
    }
}
