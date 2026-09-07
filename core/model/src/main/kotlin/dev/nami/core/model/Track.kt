package dev.nami.core.model

@JvmInline
value class TrackId(val value: String)

data class Track(
    val id: TrackId,
    val title: String,
    val artistId: ArtistId?,
    val albumId: AlbumId?,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val durationMs: Long,
    val path: String,
    val format: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    val genre: String? = null,
    val albumArtworkPath: String? = null,
    val artistName: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val replayGainDb: Float? = null,
)
