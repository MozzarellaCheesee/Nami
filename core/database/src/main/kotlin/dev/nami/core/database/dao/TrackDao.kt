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
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE tracks.deletedAt IS NULL
        ORDER BY tracks.dateAdded DESC
        """,
    )
    fun pagingSource(): PagingSource<Int, TrackWithArtwork>

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE tracks.deletedAt IS NULL
        ORDER BY tracks.dateAdded DESC
        """,
    )
    suspend fun allOrderedWithArtwork(): List<TrackWithArtwork>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: String): TrackEntity?

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE tracks.id = :id
        """,
    )
    suspend fun findByIdWithArtwork(id: String): TrackWithArtwork?

    @Query("SELECT * FROM tracks WHERE path = :path AND deletedAt IS NULL LIMIT 1")
    suspend fun findByPath(path: String): TrackEntity?

    // "IS" (not "=") so NULL artistId/albumId compare equal to NULL -- most tracks with no
    // tag-resolved artist/album still shouldn't get re-imported as a "new" duplicate.
    @Query(
        """
        SELECT * FROM tracks
        WHERE title = :title AND artistId IS :artistId AND albumId IS :albumId
        AND durationMs = :durationMs AND deletedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun findDuplicate(title: String, artistId: String?, albumId: String?, durationMs: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE tracks.albumId = :albumId AND tracks.deletedAt IS NULL
        ORDER BY tracks.discNo ASC, tracks.trackNo ASC
        """,
    )
    suspend fun tracksForAlbum(albumId: String): List<TrackWithArtwork>

    // Reactive twin of tracksForAlbum -- AlbumDetailScreen needs to see add/remove/rename live,
    // same reasoning as tracksForArtist's Flow version.
    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE tracks.albumId = :albumId AND tracks.deletedAt IS NULL
        ORDER BY tracks.discNo ASC, tracks.trackNo ASC
        """,
    )
    fun observeTracksForAlbum(albumId: String): Flow<List<TrackWithArtwork>>

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM tracks
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE tracks.artistId = :artistId AND tracks.deletedAt IS NULL
        ORDER BY albums.year DESC, albums.title ASC, tracks.discNo ASC, tracks.trackNo ASC
        """,
    )
    // Flow, not suspend -- Room auto-reruns this and re-emits whenever the tracks table changes,
    // so play counts (and anything else) update live on this screen without leaving/reentering.
    fun tracksForArtist(artistId: String): Flow<List<TrackWithArtwork>>

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

    @Query("UPDATE tracks SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: String)

    @Query("UPDATE tracks SET replayGainDb = :gainDb WHERE id = :id")
    suspend fun updateReplayGain(id: String, gainDb: Float)

    // Unconditional -- unlike setArtworkPath (import's "only if null" writer), this is for the
    // user explicitly replacing a track's own (albumless) cover.
    @Query("UPDATE tracks SET artworkPath = :path WHERE id = :id")
    suspend fun updateArtworkPath(id: String, path: String)

    // null detaches the track from any album (used by "remove from album").
    @Query("UPDATE tracks SET albumId = :albumId WHERE id = :id")
    suspend fun setAlbumId(id: String, albumId: String?)

    // Drives the "single" auto-tag -- see LibraryRepositoryImpl.syncAlbumIsSingle.
    @Query("SELECT COUNT(*) FROM tracks WHERE albumId = :albumId AND deletedAt IS NULL")
    suspend fun countByAlbum(albumId: String): Int

    // null detaches the track from any artist (used by "remove from artist").
    @Query("UPDATE tracks SET artistId = :artistId WHERE id = :id")
    suspend fun setArtistId(id: String, artistId: String?)

    @Query("SELECT * FROM tracks WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun trashedTracksFlow(): Flow<List<TrackEntity>>

    data class TrackWithArtwork(
        @Embedded val track: TrackEntity,
        val albumArtworkPath: String?,
        val artistName: String?,
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
