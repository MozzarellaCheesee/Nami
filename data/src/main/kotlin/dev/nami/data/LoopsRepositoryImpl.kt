package dev.nami.data

import dev.nami.core.database.dao.LoopDao
import dev.nami.core.database.entity.LoopEntity
import dev.nami.core.model.TrackId
import dev.nami.domain.LoopsRepository
import dev.nami.domain.SavedLoop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LoopsRepositoryImpl @Inject constructor(
    private val dao: LoopDao,
) : LoopsRepository {

    override fun loopsForTrack(trackId: TrackId): Flow<List<SavedLoop>> =
        dao.observeForTrack(trackId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(trackId: TrackId, startMs: Long, endMs: Long, name: String) {
        dao.insert(
            LoopEntity(
                trackId = trackId.value,
                startMs = startMs,
                endMs = endMs,
                name = name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun remove(id: Long) {
        dao.delete(id)
    }

    private fun LoopEntity.toDomain() = SavedLoop(id, TrackId(trackId), startMs, endMs, name, createdAt)
}
