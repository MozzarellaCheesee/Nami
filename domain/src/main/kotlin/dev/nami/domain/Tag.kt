package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

@JvmInline
value class TagId(val value: String)

/** П.md §3 "модель данных" -- user-defined color-coded tags, orthogonal to genre: a track can
 * carry several, genre stays a single string. */
data class Tag(val id: TagId, val name: String, val colorArgb: Int)

interface TagRepository {
    fun tags(): Flow<List<Tag>>
    suspend fun createTag(name: String, colorArgb: Int): TagId
    suspend fun deleteTag(id: TagId)
    fun tagsForTrack(trackId: TrackId): Flow<List<Tag>>
    suspend fun assignTag(trackId: TrackId, tagId: TagId)
    suspend fun unassignTag(trackId: TrackId, tagId: TagId)
    fun tracksForTag(tagId: TagId): Flow<List<dev.nami.core.model.Track>>
}
