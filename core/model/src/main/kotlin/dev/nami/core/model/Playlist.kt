package dev.nami.core.model

@JvmInline
value class PlaylistId(val value: String)

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val coverPath: String?,
)

data class PlaylistSummary(
    val id: PlaylistId,
    val name: String,
    val coverPath: String?,
    val trackCount: Int,
)
