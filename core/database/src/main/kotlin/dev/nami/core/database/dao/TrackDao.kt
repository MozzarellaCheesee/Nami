package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.TrackEntity

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    fun pagingSource(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNo ASC, trackNo ASC")
    suspend fun tracksForAlbum(albumId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE artistId = :artistId ORDER BY albumId ASC, trackNo ASC")
    suspend fun tracksForArtist(artistId: String): List<TrackEntity>
}
