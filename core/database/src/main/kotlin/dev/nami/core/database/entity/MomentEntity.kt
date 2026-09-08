package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-placed marker on a track's waveform (План.md §22.1 "Метки моментов") - a long-press on
 * the scrubber drops one of these with a label and color; tapping it later jumps straight to
 * [positionMs]. Independent of Lyrics/PlayHistory - purely a per-track bookmark. */
@Entity(tableName = "moments")
data class MomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val positionMs: Long,
    val label: String,
    val color: Int,
    val createdAt: Long,
    /** План.md §22.16 "Главы и закладки" - same table, different intent: a chapter/bookmark is a
     * navigation point (long track, lecture, mix), not a "best part" highlight. Reuses this table
     * instead of a separate one since the data shape (track + position + label) is identical. */
    val isChapter: Boolean = false,
)
