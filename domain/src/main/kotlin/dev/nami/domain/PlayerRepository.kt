package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.StateFlow

interface PlayerRepository {
    val state: StateFlow<PlaybackState>
    suspend fun play(queue: List<TrackId>, startIndex: Int, startMs: Long = 0)
    suspend fun toggle()
    suspend fun seek(ms: Long)
    suspend fun skipNext()
    suspend fun skipPrevious()
}
