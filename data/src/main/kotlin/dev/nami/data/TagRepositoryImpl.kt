package dev.nami.data

import dev.nami.core.database.dao.TagDao
import dev.nami.core.database.entity.TagEntity
import dev.nami.core.database.entity.TrackTagEntity
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.data.mapper.toDomain
import dev.nami.domain.Tag
import dev.nami.domain.TagId
import dev.nami.domain.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class TagRepositoryImpl @Inject constructor(
    private val tagDao: TagDao,
) : TagRepository {

    override fun tags(): Flow<List<Tag>> =
        tagDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun createTag(name: String, colorArgb: Int): TagId {
        val id = UUID.randomUUID().toString()
        tagDao.insert(TagEntity(id = id, name = name, colorArgb = colorArgb, updatedAt = System.currentTimeMillis()))
        return TagId(id)
    }

    override suspend fun deleteTag(id: TagId) {
        tagDao.delete(id.value)
    }

    override fun tagsForTrack(trackId: TrackId): Flow<List<Tag>> =
        tagDao.observeForTrack(trackId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun assignTag(trackId: TrackId, tagId: TagId) {
        tagDao.assign(TrackTagEntity(trackId = trackId.value, tagId = tagId.value, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun unassignTag(trackId: TrackId, tagId: TagId) {
        tagDao.unassign(trackId.value, tagId.value)
    }

    override fun tracksForTag(tagId: TagId): Flow<List<Track>> =
        tagDao.observeTracksForTag(tagId.value).map { rows -> rows.map { it.toDomain() } }
}

private fun TagEntity.toDomain() = Tag(TagId(id), name, colorArgb)
