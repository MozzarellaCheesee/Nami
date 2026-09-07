package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("albumId"),
        Index("artistId"),
        Index("dateAdded"),
        Index("lastPlayed"),
        Index(value = ["path"], unique = true),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val albumId: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val durationMs: Long,
    val path: String,
    val format: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val lastPlayed: Long?,
    val playCount: Int,
    val genre: String? = null,
    val deletedAt: Long? = null,
    val artworkPath: String? = null,
    /** For the "Аудиотракт" screen -- null for lossy formats lofty can't report a bit depth for. */
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    /** ReplayGain-lite: RMS-loudness gain to reach a -18dBFS target, scanned once on first play.
     * Null until scanned (or if scanning failed) -- not true EBU R128 (no true-peak/gating), just
     * simple RMS over the decoded track. */
    val replayGainDb: Float? = null,
)
