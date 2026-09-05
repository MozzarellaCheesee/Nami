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

    @Query(
        """
        SELECT * FROM artists
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.artistId = artists.id AND tracks.deletedAt IS NULL)
        ORDER BY sortName ASC
        """,
    )
    fun pagingSource(): PagingSource<Int, ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: ArtistEntity)

    @Query(
        """
        SELECT * FROM artists
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.artistId = artists.id AND tracks.deletedAt IS NULL)
        """,
    )
    suspend fun allForIndexing(): List<ArtistEntity>
}
