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
    val note: String? = null,
    val skipCount: Int = 0,
    val bpm: Float? = null,
    val musicalKey: String? = null,
    val rating: Int? = null,
    val firstPlayed: Long? = null,
    val fileHash: String? = null,
    /** Хвост группы C "CUE-поддержка" - ненулевые, когда несколько строк Track делят один
     * физический файл (образ альбома + .cue). Null для обычного трека - играет весь файл. */
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
)
