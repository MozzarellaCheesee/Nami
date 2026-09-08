package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.TagEntity
import dev.nami.core.database.entity.TrackTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    /** Snapshot for export/backup -- see BackupRepository. */
    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun allRaw(): List<TagEntity>

    /** Snapshot of every track-tag assignment for export/backup. */
    @Query("SELECT * FROM track_tags")
    suspend fun allAssignmentsRaw(): List<TrackTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN track_tags ON track_tags.tagId = tags.id
        WHERE track_tags.trackId = :trackId
        ORDER BY tags.name ASC
        """,
    )
    fun observeForTrack(trackId: String): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(crossRef: TrackTagEntity)

    @Query("DELETE FROM track_tags WHERE trackId = :trackId AND tagId = :tagId")
    suspend fun unassign(trackId: String, tagId: String)

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        INNER JOIN track_tags ON track_tags.trackId = tracks.id
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE track_tags.tagId = :tagId AND tracks.deletedAt IS NULL
        ORDER BY tracks.dateAdded DESC
        """,
    )
    fun observeTracksForTag(tagId: String): Flow<List<TrackDao.TrackWithArtwork>>
}
