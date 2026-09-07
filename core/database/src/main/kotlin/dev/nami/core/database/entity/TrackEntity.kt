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
    /** "Заметки к треку" (План.md §22.17) -- free-text personal comment, null until the user
     * writes one. */
    val note: String? = null,
    /** План.md §22.13 "правила автоочереди" ("избегать треков, скипнутых 3+ раз") -- incremented
     * when the user skips away from this track before it's played substantially (see
     * PlayerRepositoryImpl.skipNext), not on every skipNext call regardless of position. */
    val skipCount: Int = 0,
    /** BPM/key (План.md §3's Track.bpm/musicalKey) -- scanned once via BpmKeyAnalyzer, cached
     * here like replayGainDb. Null until scanned or on a decode/analysis failure. */
    val bpm: Float? = null,
    val musicalKey: String? = null,
)
