package dev.nami.domain

import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId

data class SpotifyMatchResult(
    val spotifyTitle: String,
    val spotifyArtist: String,
    val spotifyDurationMs: Long,
    val spotifyCoverUrl: String?,
    val localTrackId: TrackId?,
    val localTitle: String?,
    val localArtist: String?,
    val confidence: Float
)

data class SpotifyPlaylistAnalysis(
    val playlistName: String,
    val playlistImageUrl: String?,
    val totalTracks: Int,
    val matches: List<SpotifyMatchResult>
) {
    val matchedCount: Int get() = matches.count { it.localTrackId != null }
    val unmatchedCount: Int get() = matches.size - matchedCount
}

interface SpotifyPlaylistRepository {
    suspend fun analyzePlaylist(spotifyUrl: String): SpotifyPlaylistAnalysis
    suspend fun assemblePlaylist(name: String, matchedTrackIds: List<TrackId>): PlaylistId
}
