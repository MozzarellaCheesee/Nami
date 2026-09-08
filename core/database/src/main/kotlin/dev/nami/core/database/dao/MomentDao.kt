package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.nami.core.database.entity.MomentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MomentDao {
    @Query("SELECT * FROM moments WHERE trackId = :trackId ORDER BY positionMs ASC")
    fun observeForTrack(trackId: String): Flow<List<MomentEntity>>

    @Insert
    suspend fun insert(moment: MomentEntity)

    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun delete(id: Long)

    // Backs the "лучшие моменты" playlist (План.md §22.1) - every moment across the whole
    // library, newest first, so a track that's had several moments dropped on it appears once
    // per moment (each one is its own 30s-clip queue entry, not deduplicated).
    @Query("SELECT * FROM moments ORDER BY createdAt DESC")
    suspend fun allSnapshot(): List<MomentEntity>
}
