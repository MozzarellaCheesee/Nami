package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import dev.nami.core.database.entity.VocabularyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary ORDER BY addedAt DESC")
    suspend fun observeAllSnapshot(): List<VocabularyEntity>

    @Insert
    suspend fun insert(word: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun delete(id: Long)
}
