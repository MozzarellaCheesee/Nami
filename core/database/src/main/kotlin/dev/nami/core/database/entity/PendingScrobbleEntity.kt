package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Офлайн-очередь скробблов (ListenBrainz / Nami Server).
 * Хранит прослушанные треки при отсутствии сети для последующей гарантированной отправки.
 */
@Entity(tableName = "pending_scrobbles")
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val serverTrackId: Long?,
    val playedAt: Long,
    val durationMs: Long,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
