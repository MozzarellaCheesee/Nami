package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.AlbumEntity

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun findById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE title = :title AND artistId = :artistId LIMIT 1")
    suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity?

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC, title ASC")
    suspend fun albumsByArtist(artistId: String): List<AlbumEntity>

    @Query(
        """
        SELECT albums.id AS id, albums.title AS title, artists.name AS artistName, albums.artworkPath AS artworkPath
        FROM albums LEFT JOIN artists ON albums.artistId = artists.id
        ORDER BY albums.title ASC
        """,
    )
    fun pagingSource(): PagingSource<Int, AlbumListRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: AlbumEntity)

    @Query("UPDATE albums SET artworkPath = :path WHERE id = :id AND artworkPath IS NULL")
    suspend fun setArtworkPath(id: String, path: String)

    data class AlbumListRow(
        val id: String,
        val title: String,
        val artistName: String?,
        val artworkPath: String?,
    )
}
