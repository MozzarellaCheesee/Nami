package dev.nami.domain

/** Данные анализа трека, посчитанные сервером (см. `POST /api/library/analyze`). */
data class ServerAnalysis(
    val replayGainTrackGainDb: Float?,
    val replayGainTrackPeak: Float?,
    val r128LoudnessLufs: Float?,
    val bpm: Float?,
    val musicalKey: String?,
)

/**
 * Часть VII - self-hosted сервер как источник аудио и анализа.
 *
 * Работает только когда сервер подключён (`SettingsRepository.namiServerPreferred` + токен).
 * Все методы - «best effort»: null означает «нет сервера / трек ему не известен / сеть»,
 * и вызывающий откатывается на локальный файл и локальный анализ.
 */
interface ServerAudioRepository {

    /** true - сервер подключён и предпочитается для лирики/анализа/стрима. */
    fun isServerActive(): Boolean

    /**
     * id трека в библиотеке сервера по метаданным (`POST /api/tracks/match`), с кешем в
     * памяти на время сессии. Null - сервера нет либо трека там нет.
     */
    suspend fun serverTrackId(artist: String?, title: String, durationMs: Long): Long?

    /** Анализ трека с сервера (ReplayGain/R128/BPM/тональность). Null - недоступно. */
    suspend fun serverAnalysis(artist: String?, title: String, durationMs: Long): ServerAnalysis?

    /**
     * URL потока с сервера для уже известного `serverTrackId`, с токеном в query
     * (`<base>/api/tracks/{id}/stream/auto?token=...`). Null - сервера нет. Пиннинг
     * самоподписанного сертификата для проигрывателя тут не решается - см. реализацию.
     */
    fun serverStreamUrl(serverTrackId: Long): String?
}
