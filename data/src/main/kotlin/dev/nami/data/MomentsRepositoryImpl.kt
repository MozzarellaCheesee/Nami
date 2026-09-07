package dev.nami.data

import dev.nami.core.database.dao.MomentDao
import dev.nami.core.database.entity.MomentEntity
import dev.nami.core.model.TrackId
import dev.nami.domain.Moment
import dev.nami.domain.MomentsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MomentsRepositoryImpl @Inject constructor(
    private val dao: MomentDao,
) : MomentsRepository {

    override fun momentsForTrack(trackId: TrackId): Flow<List<Moment>> =
        dao.observeForTrack(trackId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(trackId: TrackId, positionMs: Long, label: String, colorArgb: Int, isChapter: Boolean) {
        dao.insert(
            MomentEntity(
                trackId = trackId.value,
                positionMs = positionMs,
                label = label,
                color = colorArgb,
                createdAt = System.currentTimeMillis(),
                isChapter = isChapter,
            ),
        )
    }

    override suspend fun remove(id: Long) {
        dao.delete(id)
    }

    override suspend fun allMoments(): List<Moment> = dao.allSnapshot().map { it.toDomain() }

    private fun MomentEntity.toDomain() = Moment(id, TrackId(trackId), positionMs, label, color, createdAt, isChapter)
}
