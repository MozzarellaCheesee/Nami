package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.StateFlow

data class PlayableTrack(
    val id: TrackId,
    val title: String,
    val artistName: String?,
    val path: String,
    val artworkPath: String? = null,
    val format: String? = null,
    /** Длительность, мс. Нужна для сопоставления трека с сервером (по исполнителю,
     * названию и длительности ±2 с). 0 - неизвестна, тогда серверный стрим для этого
     * трека просто не подхватится и играет локальный файл. */
    val durationMs: Long = 0L,
    /** Хвост группы C "CUE-поддержка" - ненулевые, когда несколько треков делят один физический
     * файл. Player seek'ает на cueStartMs при старте и переходит на следующий трек по достижении
     * cueEndMs, вместо естественного конца файла. */
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    val bpm: Float? = null,
)

enum class QueueOrigin { MANUAL, CONTEXT }

/** Источник воспроизведения текущего трека (Дизайн.md §4.13). */
enum class TrackPlaybackSource {
    /** Локальный файл на устройстве (воспроизведение Bit-perfect напрямую с накопителя). */
    LOCAL,
    /** Офлайн-кеш приложения, предварительно скачанный с сервера. */
    CACHE,
    /** Потоковый стриминг с сервера NAMI по сети. */
    SERVER,
    /** Сервер недоступен / ошибка сети для удалённого трека. */
    UNAVAILABLE,
}

/** OFF: play through the queue once and stop. ALL: loop the whole queue. ONE: loop just the
 * current track. Maps 1:1 to ExoPlayer's own REPEAT_MODE_* constants. */
enum class RepeatMode { OFF, ALL, ONE }

/** A-B loop range (План.md §22.2) - while set, [PlayerRepository] seeks back to [startMs] the
 * moment playback reaches [endMs], on the current track only. */
data class LoopRange(val startMs: Long, val endMs: Long)

data class QueueTrack(
    val id: TrackId,
    val title: String,
    val artistName: String?,
    val artworkPath: String? = null,
    val format: String? = null,
)

data class QueueItem(
    val track: QueueTrack,
    val origin: QueueOrigin,
)

data class PlayerQueue(
    val nowPlaying: QueueTrack?,
    val upcoming: List<QueueItem>,
    // Only the immediately preceding track (not a full history) - just enough to render a
    // "swipe right reveals this" preview in Now Playing/MiniPlayer without a bigger history
    // feature.
    val previousTrack: QueueTrack? = null,
) {
    companion object {
        val EMPTY = PlayerQueue(nowPlaying = null, upcoming = emptyList(), previousTrack = null)
    }
}

interface PlayerRepository {
    val state: StateFlow<PlaybackState>
    val queue: StateFlow<PlayerQueue>
    /** Текущий источник воспроизведения трека (локальный / кеш / сервер / недоступен). */
    val playbackSource: StateFlow<TrackPlaybackSource>
    /** Перечитать источник текущего серверного трека после скачивания в офлайн-кеш. */
    suspend fun refreshCurrentSource() {}
    /** Виджеты (группа E) вызывают toggle/skipNext/etc. из свежего процесса (Android часто убивает
     * фоновый процесс приложения, тап по виджету поднимает его заново) - MediaController
     * подключается к сервису асинхронно, и сразу после холодного старта ещё не готов, из-за чего
     * toggle()/skipNext() читали controller == null и молча ничего не делали ("кнопки не
     * работают"). Ждёт готовности контроллера (с таймаутом) перед тем как виджет читает state/
     * queue напрямую - toggle/seek/skipNext/skipPrevious/skipToPreviousTrack ждут сами внутри
     * себя и этого явного вызова не требуют. */
    suspend fun awaitReady()
    /** Bumped when ExoPlayer advances to the next track on its own (the current one simply ended)
     * - as opposed to a skip button, a swipe, or a list tap, which the UI already animates for
     * itself. Lets Now Playing/MiniPlayer play the same slide transition for a natural track
     * change instead of the cover just silently jumping to the next one. */
    val autoAdvanceSignal: StateFlow<Int>
    /** Whether the current queue (from the currently-playing item forward) is in a shuffled
     * order right now - real, not a UI stub: [setShuffleEnabled] actually reorders the live
     * playback queue and can restore the exact pre-shuffle order. */
    val shuffleEnabled: StateFlow<Boolean>
    /** Real ExoPlayer repeat mode - see [RepeatMode]. */
    val repeatMode: StateFlow<RepeatMode>
    suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long = 0)
    /** Запускает очередь ровно в переданном порядке, без библиотечных «цепочек» треков. */
    suspend fun playInOrder(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long = 0) {
        play(tracks, startIndex, startMs)
    }
    suspend fun toggle()
    suspend fun seek(ms: Long)
    suspend fun skipNext()
    /** Переводит на следующий трек плавным overlap-кроссфейдом. Если воспроизведение вручную
     * поставлено на паузу, сохраняет паузу и просто выбирает следующий трек. */
    suspend fun crossfadeNext(durationMs: Long? = null) { skipNext() }

    companion object {
        const val DEFAULT_CROSSFADE_MS = 5000L
        /** В разборе библиотеки (карточный режим) кроссфейд ускорен в 2 раза: 2500 мс вместо 5000 мс */
        const val CARD_SORT_CROSSFADE_MS = 2500L
    }
    /** Threshold-based: restarts the current track if it's already played past a few seconds,
     * only moving to the actual previous track on a second call. Matches standard media-player
     * "prev button" behavior. */
    suspend fun skipPrevious()
    /** Always moves to the actual previous track, ignoring playback position - for swipe
     * gestures, where the elapsed-time restart of [skipPrevious] reads as "swiped but nothing
     * happened" since the first swipe just replays the current track. */
    suspend fun skipToPreviousTrack()
    /** Stops playback entirely and clears the queue (nowPlaying becomes null). */
    suspend fun stop()
    suspend fun addToQueue(track: PlayableTrack)
    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int)
    suspend fun removeQueueItem(index: Int)
    /** Removes any currently playing/queued item whose id is in [ids] (e.g. after a library delete). */
    suspend fun removeTracks(ids: Set<TrackId>)
    /** Reorders the live queue in place - true shuffles everything after (and not) the currently
     * playing item, keeping that item where it is; false restores the exact order the queue had
     * the moment it was last shuffled. A no-op if [enabled] already matches the current state, or
     * if false is requested with nothing to restore (shuffle was never turned on this queue). */
    suspend fun setShuffleEnabled(enabled: Boolean)
    suspend fun setRepeatMode(mode: RepeatMode)
    /** Milliseconds left on the sleep timer, ticking down once per second; null when no timer is
     * running. Reaching 0 pauses playback and clears back to null. */
    val sleepTimerRemainingMs: StateFlow<Long?>
    /** Starts (replacing any running one) a timer that pauses playback after [durationMs]. */
    suspend fun startSleepTimer(durationMs: Long)
    suspend fun cancelSleepTimer()

    /** A-B loop currently in effect, null when off. Cleared automatically on track change (an A-B
     * range only makes sense for the track it was set on). */
    val activeLoop: StateFlow<LoopRange?>
    suspend fun setActiveLoop(loop: LoopRange?)
}
