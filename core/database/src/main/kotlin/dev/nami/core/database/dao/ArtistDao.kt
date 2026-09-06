package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.ArtistEntity

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun findById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): ArtistEntity?

    // Falls back to the artist's first album cover when they have no photo of their own --
    // matches AlbumDao/TrackDao's "computed column via subquery, no migration" pattern.
    @Query(
        """
        SELECT artists.id AS id, artists.name AS name, artists.sortName AS sortName,
               COALESCE(artists.photoPath, (
                   SELECT albums.artworkPath FROM albums
                   WHERE albums.artistId = artists.id AND albums.artworkPath IS NOT NULL
                   ORDER BY albums.title ASC LIMIT 1
               )) AS photoPath
        FROM artists
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.artistId = artists.id AND tracks.deletedAt IS NULL)
        ORDER BY sortName ASC
        """,
    )
    fun pagingSource(): PagingSource<Int, ArtistWithPhoto>

    @Query(
        """
        SELECT artists.id AS id, artists.name AS name, artists.sortName AS sortName,
               COALESCE(artists.photoPath, (
                   SELECT albums.artworkPath FROM albums
                   WHERE albums.artistId = artists.id AND albums.artworkPath IS NOT NULL
                   ORDER BY albums.title ASC LIMIT 1
               )) AS photoPath
        FROM artists WHERE id = :id
        """,
    )
    suspend fun findByIdWithPhoto(id: String): ArtistWithPhoto?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: ArtistEntity)

    @Query("UPDATE artists SET photoPath = :path WHERE id = :id AND photoPath IS NULL")
    suspend fun setPhotoPath(id: String, path: String)

    @Query("UPDATE artists SET name = :name, sortName = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    // Unconditional -- unlike setPhotoPath (import's "only if null" writer), this is for the
    // user explicitly replacing an artist's photo.
    @Query("UPDATE artists SET photoPath = :path WHERE id = :id")
    suspend fun updatePhotoPath(id: String, path: String)

    @Query(
        """
        SELECT * FROM artists
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.artistId = artists.id AND tracks.deletedAt IS NULL)
        """,
    )
    suspend fun allForIndexing(): List<ArtistEntity>

    data class ArtistWithPhoto(val id: String, val name: String, val sortName: String, val photoPath: String?)
}
