package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        WHERE tracks.deletedAt IS NULL
        ORDER BY tracks.dateAdded DESC
        """,
    )
    fun pagingSource(): PagingSource<Int, TrackWithArtwork>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE path = :path AND deletedAt IS NULL LIMIT 1")
    suspend fun findByPath(path: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        WHERE tracks.albumId = :albumId AND tracks.deletedAt IS NULL
        ORDER BY tracks.discNo ASC, tracks.trackNo ASC
        """,
    )
    suspend fun tracksForAlbum(albumId: String): List<TrackWithArtwork>

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        WHERE tracks.artistId = :artistId AND tracks.deletedAt IS NULL
        ORDER BY albums.year DESC, albums.title ASC, tracks.discNo ASC, tracks.trackNo ASC
        """,
    )
    suspend fun tracksForArtist(artistId: String): List<TrackWithArtwork>

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

    @Query("UPDATE tracks SET artworkPath = :path WHERE id = :id AND artworkPath IS NULL")
    suspend fun setArtworkPath(id: String, path: String)

    @Query("SELECT * FROM tracks WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun trashedTracksFlow(): Flow<List<TrackEntity>>

    data class TrackWithArtwork(
        @Embedded val track: TrackEntity,
        val albumArtworkPath: String?,
    )

    data class TrackIndexRow(
        val id: String,
        val title: String,
        val artistName: String?,
        val albumName: String?,
        val format: String,
        val year: Int?,
    )
}
