package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.AlbumArtistCrossRef
import dev.nami.core.database.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

// Shared by every query below that lists albums: an album can be credited to more than one
// artist (album_artists), so the display name is every credited artist's name, comma-joined,
// rather than a plain LEFT JOIN on albums.artistId (which only ever showed one).
private const val ARTIST_NAMES_SUBQUERY = """
    (SELECT GROUP_CONCAT(artists.name, ', ') FROM album_artists
     JOIN artists ON artists.id = album_artists.artistId
     WHERE album_artists.albumId = albums.id)
"""

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun findById(id: String): AlbumEntity?

    // Room auto-invalidates this on any write to `albums` (rename, cover, isSingle, artist) --
    // AlbumDetailScreen needs this to actually see its own edits without leaving and re-entering.
    @Query("SELECT * FROM albums WHERE id = :id")
    fun observeById(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE title = :title AND artistId = :artistId LIMIT 1")
    suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity?

    @Query(
        """
        SELECT * FROM albums
        WHERE EXISTS (SELECT 1 FROM album_artists WHERE album_artists.albumId = albums.id AND album_artists.artistId = :artistId)
        AND EXISTS (SELECT 1 FROM tracks WHERE tracks.albumId = albums.id AND tracks.deletedAt IS NULL)
        ORDER BY year DESC, title ASC
        """,
    )
    suspend fun albumsByArtist(artistId: String): List<AlbumEntity>

    @Query(
        """
        SELECT albums.id AS id, albums.title AS title, $ARTIST_NAMES_SUBQUERY AS artistName, albums.artworkPath AS artworkPath
        FROM albums
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.albumId = albums.id AND tracks.deletedAt IS NULL)
        ORDER BY albums.title ASC
        """,
    )
    fun pagingSource(): PagingSource<Int, AlbumListRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: AlbumEntity)

    @Query("UPDATE albums SET artworkPath = :path WHERE id = :id AND artworkPath IS NULL")
    suspend fun setArtworkPath(id: String, path: String)

    @Query("UPDATE albums SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    // Unconditional -- unlike setArtworkPath (import's "only if null" writer), this is for the
    // user explicitly replacing an album's cover.
    @Query("UPDATE albums SET artworkPath = :path WHERE id = :id")
    suspend fun updateArtworkPath(id: String, path: String)

    @Query("UPDATE albums SET isSingle = :isSingle WHERE id = :id")
    suspend fun setIsSingle(id: String, isSingle: Boolean)

    // Replaces the "primary" artist column AND resets album_artists to just that one artist --
    // this is the single-artist picker (AlbumDetailScreen's "Изменить артиста"), distinct from
    // addArtist/removeArtist below which manage the multi-artist credit list without touching it.
    @Query("UPDATE albums SET artistId = :artistId WHERE id = :id")
    suspend fun setArtistId(id: String, artistId: String?)

    @Query("DELETE FROM album_artists WHERE albumId = :albumId")
    suspend fun clearArtists(albumId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addArtist(ref: AlbumArtistCrossRef)

    @Query("DELETE FROM album_artists WHERE albumId = :albumId AND artistId = :artistId")
    suspend fun removeArtist(albumId: String, artistId: String)

    @Query("SELECT artistId FROM album_artists WHERE albumId = :albumId")
    fun observeArtistIdsForAlbum(albumId: String): Flow<List<String>>

    @Query(
        """
        SELECT artists.* FROM artists
        JOIN album_artists ON artists.id = album_artists.artistId
        WHERE album_artists.albumId = :albumId
        ORDER BY artists.name ASC
        """,
    )
    fun observeArtistsForAlbum(albumId: String): Flow<List<dev.nami.core.database.entity.ArtistEntity>>

    @Query(
        """
        SELECT albums.id AS id, albums.title AS title, $ARTIST_NAMES_SUBQUERY AS artistName, albums.artworkPath AS artworkPath
        FROM albums
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.albumId = albums.id AND tracks.deletedAt IS NULL)
        ORDER BY albums.title DESC
        LIMIT :limit
        """,
    )
    fun observeRecentAlbums(limit: Int): Flow<List<AlbumListRow>>

    @Query(
        """
        SELECT albums.id AS id, albums.title AS title, $ARTIST_NAMES_SUBQUERY AS artistName, albums.artworkPath AS artworkPath
        FROM albums
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.albumId = albums.id AND tracks.deletedAt IS NULL)
        """,
    )
    suspend fun allForIndexing(): List<AlbumListRow>

    data class AlbumListRow(
        val id: String,
        val title: String,
        val artistName: String?,
        val artworkPath: String?,
    )
}
