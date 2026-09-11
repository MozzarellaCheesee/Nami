package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.PendingScrobbleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingScrobbleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingScrobbleEntity): Long

    @Query("SELECT * FROM pending_scrobbles ORDER BY playedAt ASC")
    suspend fun getAll(): List<PendingScrobbleEntity>

    @Query("SELECT * FROM pending_scrobbles ORDER BY playedAt ASC")
    fun observeAll(): Flow<List<PendingScrobbleEntity>>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun count(): Int

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_scrobbles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE pending_scrobbles SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)
}
