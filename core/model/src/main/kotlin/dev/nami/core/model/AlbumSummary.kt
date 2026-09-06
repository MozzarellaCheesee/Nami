package dev.nami.core.model

data class AlbumSummary(
    val id: AlbumId,
    val title: String,
    val artistName: String?,
    val artworkPath: String?,
    val year: Int? = null,
    val isSingle: Boolean = false,
)
