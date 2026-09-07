package dev.nami.core.model

data class TagResult(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val genre: String?,
    val year: Int?,
    val durationMs: Long,
    val artwork: ByteArray?,
    val artworkMime: String?,
    /** Embedded USLT/LYRICS/©lyr tag text, raw -- may be a full LRC blob or plain unsynced text. */
    val lyrics: String? = null,
)
