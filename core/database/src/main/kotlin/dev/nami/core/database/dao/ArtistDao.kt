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

    @Query("SELECT * FROM artists ORDER BY sortName ASC")
    fun pagingSource(): PagingSource<Int, ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: ArtistEntity)

    @Query("SELECT * FROM artists")
    suspend fun allForIndexing(): List<ArtistEntity>
}
