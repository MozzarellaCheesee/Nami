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
)

enum class QueueOrigin { MANUAL, CONTEXT }

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
    // Only the immediately preceding track (not a full history) -- just enough to render a
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
    /** Bumped when ExoPlayer advances to the next track on its own (the current one simply ended)
     * -- as opposed to a skip button, a swipe, or a list tap, which the UI already animates for
     * itself. Lets Now Playing/MiniPlayer play the same slide transition for a natural track
     * change instead of the cover just silently jumping to the next one. */
    val autoAdvanceSignal: StateFlow<Int>
    suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long = 0)
    suspend fun toggle()
    suspend fun seek(ms: Long)
    suspend fun skipNext()
    /** Threshold-based: restarts the current track if it's already played past a few seconds,
     * only moving to the actual previous track on a second call. Matches standard media-player
     * "prev button" behavior. */
    suspend fun skipPrevious()
    /** Always moves to the actual previous track, ignoring playback position -- for swipe
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
}
