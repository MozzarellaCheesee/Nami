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
    private val serverLibraryRepository: dev.nami.domain.ServerLibraryRepository,
) : ServerAudioRepository {

    /** key = "artist|title|durSec" -> id сервера (или NOT_FOUND если искали и не нашли). */
    private val idCache = ConcurrentHashMap<String, Long>()

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
        val cached = idCache[key]
        if (cached != null) return if (cached == NOT_FOUND) null else cached
        return withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val id = NamiServerClient.matchTrackIds(cfg, listOf(Triple(artist, title, durationMs)))
                ?.firstOrNull()
            idCache[key] = id ?: NOT_FOUND
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

    override suspend fun serverWaveform(artist: String?, title: String, durationMs: Long): List<Float>? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val id = serverTrackId(artist, title, durationMs) ?: return@withContext null
            NamiServerClient.waveform(cfg, id)?.takeIf { it.isNotEmpty() }
        }

    override fun serverStreamUrl(serverTrackId: Long): String? {
        val cfg = activeConfig() ?: return null
        return streamUrlOn(cfg.baseUrl, cfg.token, serverTrackId)
    }

    /** URL потока. `https://<IP>` с самоподписанным сертификатом ExoPlayer тянет через
     * OkHttp-датасорс с пиннингом отпечатка (см. player/net/PinnedHttpDataSource.kt). */
    private fun streamUrlOn(base: String, token: String, id: Long): String =
        "$base/api/tracks/$id/stream/auto?token=$token"

    override suspend fun serverStreamUrls(tracks: List<Triple<String?, String, Long>>): List<String?> =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext List(tracks.size) { null }
            val ids = NamiServerClient.matchTrackIds(cfg, tracks)
                ?: return@withContext List(tracks.size) { null }
            ids.forEachIndexed { i, id ->
                if (i < tracks.size) {
                    val (artist, title, dur) = tracks[i]
                    idCache[cacheKey(artist, title, dur)] = id ?: NOT_FOUND
                }
            }
            ids.map { id ->
                id ?: return@map null
                // Скачанный в офлайн файл - приоритетнее сети.
                serverLibraryRepository.cachedFile(id)?.let { return@map android.net.Uri.fromFile(it).toString() }
                streamUrlOn(cfg.baseUrl, cfg.token, id)
            }
        }

    override suspend fun fetchFriendsNowPlaying(): List<dev.nami.domain.FriendNowPlaying>? {
        val cfg = activeConfig() ?: return null
        val items = withContext(Dispatchers.IO) {
            NamiServerClient.nowPlaying(cfg)
        } ?: return null
        return items.map {
            dev.nami.domain.FriendNowPlaying(
                userId = it.userId,
                username = it.username,
                trackId = it.trackId,
                title = it.title,
                artist = it.artist,
                positionMs = it.positionMs,
                updatedAt = it.updatedAt,
            )
        }
    }

    companion object {
        private const val NOT_FOUND = -1L

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
