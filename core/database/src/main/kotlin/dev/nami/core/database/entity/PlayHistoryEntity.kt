package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** B1 "Статистика" (План.md §23.22) -- one row per "actually listened" event, the same threshold
 * PlayerRepositoryImpl already uses for incrementPlayCount (30s or 50%, whichever first). Records
 * the track's full duration as the listened amount rather than tracking exact seconds heard past
 * the threshold -- a real per-second listen log would need its own tick-based writer; this is the
 * cheap version that still gives a truthful "minutes of music per day" contribution grid. */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    val durationMs: Long,
)
