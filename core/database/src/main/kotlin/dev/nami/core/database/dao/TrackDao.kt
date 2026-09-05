package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE deletedAt IS NULL ORDER BY dateAdded DESC")
    fun pagingSource(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE path = :path AND deletedAt IS NULL LIMIT 1")
    suspend fun findByPath(path: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM tracks WHERE albumId = :albumId AND deletedAt IS NULL ORDER BY discNo ASC, trackNo ASC")
    suspend fun tracksForAlbum(albumId: String): List<TrackEntity>

    @Query(
        """
        SELECT tracks.* FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        WHERE tracks.artistId = :artistId AND tracks.deletedAt IS NULL
        ORDER BY albums.year DESC, albums.title ASC, tracks.discNo ASC, tracks.trackNo ASC
        """,
    )
    suspend fun tracksForArtist(artistId: String): List<TrackEntity>

    @Query(
        """
        SELECT tracks.id AS id, tracks.title AS title, artists.name AS artistName,
               albums.title AS albumName, tracks.format AS format, albums.year AS year
        FROM tracks
        LEFT JOIN artists ON tracks.artistId = artists.id
        LEFT JOIN albums ON tracks.albumId = albums.id
        WHERE tracks.deletedAt IS NULL
        """,
    )
    suspend fun allForIndexing(): List<TrackIndexRow>

    @Query("UPDATE tracks SET deletedAt = :deletedAt, path = :path WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?, path: String)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM tracks WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun trashedTracksFlow(): Flow<List<TrackEntity>>

    data class TrackIndexRow(
        val id: String,
        val title: String,
        val artistName: String?,
        val albumName: String?,
        val format: String,
        val year: Int?,
    )
}
