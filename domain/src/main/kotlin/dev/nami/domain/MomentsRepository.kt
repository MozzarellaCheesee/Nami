package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

data class Moment(
    val id: Long = 0,
    val trackId: TrackId,
    val positionMs: Long,
    val label: String,
    val colorArgb: Int,
    val createdAt: Long,
    /** План.md §22.16 "Главы и закладки" - same marker shape, different intent: a navigation
     * point (long track/lecture/mix), not a "best part" highlight. */
    val isChapter: Boolean = false,
)

/** "Метки моментов" from План.md §22.1 - a long-press on the waveform scrubber drops a labeled,
 * colored marker at that position; tapping it seeks straight there. */
interface MomentsRepository {
    fun momentsForTrack(trackId: TrackId): Flow<List<Moment>>
    suspend fun add(trackId: TrackId, positionMs: Long, label: String, colorArgb: Int, isChapter: Boolean = false)
    suspend fun remove(id: Long)

    /** Every moment across the whole library, newest first - backs the "только лучшие моменты"
     * playlist (План.md §22.1): each one plays as a ~30s clip starting at its position. */
    suspend fun allMoments(): List<Moment>
}
