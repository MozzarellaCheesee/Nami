package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.StateFlow

data class PlayableTrack(
    val id: TrackId,
    val title: String,
    val artistName: String?,
    val path: String,
)

enum class QueueOrigin { MANUAL, CONTEXT }

data class QueueTrack(
    val id: TrackId,
    val title: String,
    val artistName: String?,
)

data class QueueItem(
    val track: QueueTrack,
    val origin: QueueOrigin,
)

data class PlayerQueue(
    val nowPlaying: QueueTrack?,
    val upcoming: List<QueueItem>,
) {
    companion object {
        val EMPTY = PlayerQueue(nowPlaying = null, upcoming = emptyList())
    }
}

interface PlayerRepository {
    val state: StateFlow<PlaybackState>
    val queue: StateFlow<PlayerQueue>
    suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long = 0)
    suspend fun toggle()
    suspend fun seek(ms: Long)
    suspend fun skipNext()
    suspend fun skipPrevious()
    suspend fun addToQueue(track: PlayableTrack)
    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int)
    suspend fun removeQueueItem(index: Int)
    /** Removes any currently playing/queued item whose id is in [ids] (e.g. after a library delete). */
    suspend fun removeTracks(ids: Set<TrackId>)
}
