package dev.nami.core.model

@JvmInline
value class AlbumId(val value: String)

data class Album(
    val id: AlbumId,
    val title: String,
    val artistId: ArtistId?,
    val year: Int?,
    val artworkPath: String?,
)
