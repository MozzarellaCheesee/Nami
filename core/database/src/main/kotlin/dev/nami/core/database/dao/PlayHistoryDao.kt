package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.nami.core.database.entity.PlayHistoryEntity

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(row: PlayHistoryEntity)

    /** Есть ли уже такое прослушивание. Пара (trackId, playedAt) идентифицирует запись:
     * первичный ключ здесь автоинкрементный, поэтому повторная вставка той же записи,
     * вернувшейся при обратной синхронизации, молча создала бы дубль и задвоила
     * статистику «часто слушаемое».
     *
     * ponytail: проверка перед вставкой, а не UNIQUE-индекс - индекс потребовал бы
     * миграции Room с чисткой уже накопившихся дублей. Перейти на индекс, если записи
     * истории начнут создаваться конкурентно. */
    @Query("SELECT EXISTS(SELECT 1 FROM play_history WHERE trackId = :trackId AND playedAt = :playedAt)")
    suspend fun exists(trackId: String, playedAt: Long): Boolean

    /** Everything since [since] (epoch ms) - aggregated into days in Kotlin (LibraryRepositoryImpl),
     * not SQL, since a fixed local-timezone day boundary is simplest to reason about there. Table
     * stays small (one row per actually-listened track, not per tick), so this is fine unindexed. */
    @Query("SELECT * FROM play_history WHERE playedAt > :since ORDER BY playedAt ASC")
    suspend fun since(since: Long): List<PlayHistoryEntity>
}
