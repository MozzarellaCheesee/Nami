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

    /** key = "artist|title|durSec" -> id сервера (или NOT_FOUND если искали и не нашли).
     *
     * Отрицательный ответ кешируется намеренно: трека может не быть на сервере вовсе, и без
     * этого сопоставление шло бы в сеть при каждом его запуске. Срок жизни у него короче -
     * трек мог появиться на сервере уже после того, как мы про него спросили. */
    private val idCache = TtlCache<String, Long>(maxEntries = 2_000, ttlMs = ID_TTL_MS)

    /** Тот же ключ -> анализ трека с сервера. Чтобы параллельные проверки (ReplayGain и
     * BPM/тональность идут разными корутинами) не дёргали `/api/tracks/{id}` дважды.
     *
     * Живёт дольше id: пересчитывать анализ на сервере незачем, он не меняется сам по себе. */
    private val analysisCache = TtlCache<String, ServerAnalysis>(maxEntries = 500, ttlMs = ANALYSIS_TTL_MS)

    /** Данные разных серверов ключами не различаются, поэтому при смене сервера или токена
     * старые ответы надо выбрасывать целиком, а не показывать чужую библиотеку. */
    private var cachedIdentity: String? = null

    private suspend fun dropCachesIfServerChanged() {
        val identity = listOf(
            settingsRepository.namiServerToken.value.orEmpty(),
            settingsRepository.namiServerUrl.value,
        ).joinToString("|")
        if (cachedIdentity == identity) return
        cachedIdentity = identity
        idCache.clear()
        analysisCache.clear()
    }

    override fun isServerActive(): Boolean =
        !settingsRepository.namiServerToken.value.isNullOrBlank() &&
            settingsRepository.namiServerUrl.value.isNotBlank()

    /** Только снимок настроек: этот метод вызывается и из синхронных UI-путей URL. */
    private fun activeConfig(): NamiServerClient.Config? {
        if (!isServerActive()) return null
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        val bases = settingsRepository.namiServerUrl.value.split('\n', ',')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val base = bases.firstOrNull() ?: return null
        return NamiServerClient.Config(base, token, cert, bases)
    }

    private fun cacheKey(artist: String?, title: String, durationMs: Long) =
        "${artist?.trim()?.lowercase().orEmpty()}|${title.trim().lowercase()}|${durationMs / 1000}"

    override suspend fun serverTrackId(artist: String?, title: String, durationMs: Long): Long? {
        dropCachesIfServerChanged()
        val id = idCache.get(cacheKey(artist, title, durationMs)) {
            withContext(Dispatchers.IO) {
                val cfg = activeConfig() ?: return@withContext null
                NamiServerClient.matchTrackIds(cfg, listOf(Triple(artist, title, durationMs)))
                    ?.firstOrNull() ?: NOT_FOUND
            }
        }
        return id?.takeIf { it != NOT_FOUND }
    }

    override suspend fun serverAnalysis(artist: String?, title: String, durationMs: Long): ServerAnalysis? {
        dropCachesIfServerChanged()
        return analysisCache.get(cacheKey(artist, title, durationMs)) {
            val id = serverTrackId(artist, title, durationMs) ?: return@get null
            withContext(Dispatchers.IO) {
                val cfg = activeConfig() ?: return@withContext null
                val obj = NamiServerClient.trackDetail(cfg, id) ?: return@withContext null
                parseAnalysis(obj)
            }
        }
    }

    override suspend fun requestServerAnalysis(artist: String?, title: String, durationMs: Long): ServerAnalysis? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val id = serverTrackId(artist, title, durationMs) ?: return@withContext null
            val obj = NamiServerClient.analyzeTrack(cfg, id) ?: return@withContext null
            val base = parseAnalysis(obj) ?: return@withContext null
            val bars = obj.optJSONArray("waveform")?.let { arr ->
                (0 until arr.length()).map { arr.optDouble(it).toFloat() }
            }?.takeIf { it.isNotEmpty() }
            val full = base.copy(waveform = bars)
            // Кладём в тот же кеш, что и serverAnalysis: повторный вопрос про этот трек не
            // должен снова ходить в сеть.
            analysisCache.put(cacheKey(artist, title, durationMs), full)
            full
        }

    override suspend fun serverWaveform(artist: String?, title: String, durationMs: Long): List<Float>? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val id = serverTrackId(artist, title, durationMs) ?: return@withContext null
            NamiServerClient.waveform(cfg, id)?.takeIf { it.isNotEmpty() }
        }

    override fun serverStreamUrl(serverTrackId: Long): String? {
        serverLibraryRepository.cachedFile(serverTrackId)?.let {
            return android.net.Uri.fromFile(it).toString()
        }
        val cfg = activeConfig() ?: return null
        return streamUrlOn(cfg.baseUrl, cfg.token, serverTrackId)
    }

    override fun serverArtworkUrl(serverTrackId: Long): String? {
        val cfg = activeConfig() ?: return null
        // 1. Если обложка уже сохранена в локальном офлайн-кеше - отдаём прямой файл
        serverLibraryRepository.cachedArtwork(serverTrackId)?.let {
            return android.net.Uri.fromFile(it).toString()
        }
        // 2. Иначе отдаём сетевой URL ручки обложки с сервера с токеном
        return "${cfg.baseUrl}/api/tracks/$serverTrackId/artwork?token=${cfg.token}"
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
                    // Пакетное сопоставление уже дало ответы - раскладываем их по кешу, чтобы
                    // следующий поштучный вопрос про эти треки не ходил в сеть.
                    idCache.put(cacheKey(artist, title, dur), id ?: NOT_FOUND)
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

        /** Полчаса: трек мог появиться на сервере уже после того, как мы про него спросили,
         * и вечно помнить "его там нет" нельзя. */
        private const val ID_TTL_MS = 30 * 60 * 1000L

        /** Шесть часов: посчитанный анализ сам по себе не меняется, а перезапрос стоит дорого -
         * при промахе сервер декодирует файл целиком. */
        private const val ANALYSIS_TTL_MS = 6 * 60 * 60 * 1000L

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
