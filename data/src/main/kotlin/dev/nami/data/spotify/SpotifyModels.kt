package dev.nami.data.spotify

data class SpotifyTrackMeta(
    val spotifyId: String,
    val title: String,
    val artists: List<String>,
    val albumName: String?,
    val trackNo: Int,
    val durationMs: Long,
    val isrc: String?,
    val coverUrl: String?,
    val year: String?
)

data class SpotifyPlaylistInfo(
    val id: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val ownerName: String?,
    val totalTracks: Int,
    val tracks: List<SpotifyTrackMeta>
)
