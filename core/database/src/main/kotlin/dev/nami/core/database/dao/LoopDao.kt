package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.LoopEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LoopDao {
    @Query("SELECT * FROM loops WHERE trackId = :trackId ORDER BY createdAt DESC")
    fun observeForTrack(trackId: String): Flow<List<LoopEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(loop: LoopEntity)

    @Query("DELETE FROM loops WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM loops ORDER BY createdAt DESC")
    suspend fun allSnapshot(): List<LoopEntity>
}
