package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.nami.core.database.entity.PlayHistoryEntity

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(row: PlayHistoryEntity)

    /** Everything since [since] (epoch ms) - aggregated into days in Kotlin (LibraryRepositoryImpl),
     * not SQL, since a fixed local-timezone day boundary is simplest to reason about there. Table
     * stays small (one row per actually-listened track, not per tick), so this is fine unindexed. */
    @Query("SELECT * FROM play_history WHERE playedAt >= :since ORDER BY playedAt ASC")
    suspend fun since(since: Long): List<PlayHistoryEntity>
}
