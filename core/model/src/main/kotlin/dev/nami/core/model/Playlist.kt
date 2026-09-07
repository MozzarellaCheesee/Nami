package dev.nami.core.model

@JvmInline
value class PlaylistId(val value: String)

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val coverPath: String?,
    /** The system "Любимые треки" (Spotify-style Liked Songs) playlist -- own heart-gradient
     * cover instead of coverPath, name/cover locked, can't be deleted. */
    val isLiked: Boolean = false,
)

data class PlaylistSummary(
    val id: PlaylistId,
    val name: String,
    val coverPath: String?,
    val trackCount: Int,
    val isLiked: Boolean = false,
)
