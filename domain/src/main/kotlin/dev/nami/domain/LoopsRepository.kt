package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

data class SavedLoop(
    val id: Long = 0,
    val trackId: TrackId,
    val startMs: Long,
    val endMs: Long,
    val name: String,
    val createdAt: Long,
)

/** Named, persisted A-B loops (План.md §22.2) -- distinct from [PlayerRepository.activeLoop],
 * which is the live "looping right now" state. This is just the saved presets a user can
 * re-activate later. */
interface LoopsRepository {
    fun loopsForTrack(trackId: TrackId): Flow<List<SavedLoop>>
    suspend fun save(trackId: TrackId, startMs: Long, endMs: Long, name: String)
    suspend fun remove(id: Long)
}
