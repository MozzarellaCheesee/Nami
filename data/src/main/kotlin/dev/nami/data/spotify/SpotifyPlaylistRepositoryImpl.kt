package dev.nami.data.spotify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SpotifyMatchResult
import dev.nami.domain.SpotifyPlaylistAnalysis
import dev.nami.domain.SpotifyPlaylistRepository
import dev.nami.core.model.Track
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SpotifyPlaylistRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository
) : SpotifyPlaylistRepository {

    override suspend fun analyzePlaylist(spotifyUrl: String): SpotifyPlaylistAnalysis {
        val playlistId = SpotifyApiClient.parsePlaylistId(spotifyUrl)
            ?: throw IllegalArgumentException("Неверный URL плейлиста Spotify")

        val clientId = settingsRepository.spotifyClientId.value
        val clientSecret = settingsRepository.spotifyClientSecret.value
        
        if (clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty()) {
            throw IllegalStateException("API ключи Spotify не настроены в настройках")
        }

        val playlistInfo = SpotifyApiClient.fetchPlaylist(playlistId, clientId, clientSecret)
            ?: throw IllegalStateException("Не удалось загрузить плейлист из Spotify")

        val localTracks = libraryRepository.allTracksOrdered()

        val matches = playlistInfo.tracks.map { spotifyTrack ->
            matchWithLocal(spotifyTrack, localTracks)
        }

        return SpotifyPlaylistAnalysis(
            playlistName = playlistInfo.name,
            playlistImageUrl = playlistInfo.imageUrl,
            totalTracks = playlistInfo.totalTracks,
            matches = matches
        )
    }

    override suspend fun assemblePlaylist(name: String, matchedTrackIds: List<TrackId>): PlaylistId {
        val playlistId = playlistRepository.createPlaylist(name)
        matchedTrackIds.forEach { trackId ->
            playlistRepository.addTrack(playlistId, trackId)
        }
        return playlistId
    }

    private fun matchWithLocal(spotifyTrack: SpotifyTrackMeta, localTracks: List<Track>): SpotifyMatchResult {
        val spotifyArtist = spotifyTrack.artists.firstOrNull() ?: ""
        val normSpotTitle = normalize(spotifyTrack.title)
        val normSpotArtist = normalize(spotifyArtist)
        
        var bestMatch: Track? = null
        var bestScore = 0f

        for (local in localTracks) {
            val normLocTitle = normalize(local.title)
            val normLocArtist = normalize(local.artistName ?: "")
            
            var score = 0f
            
            if (normSpotTitle == normLocTitle && normSpotArtist == normLocArtist) {
                score = 1.0f
            } else if (normSpotTitle.isNotEmpty() && normLocTitle.contains(normSpotTitle)) {
                if (normSpotArtist == normLocArtist) {
                    score = 0.9f
                } else if (normSpotArtist.isNotEmpty() && normLocArtist.contains(normSpotArtist)) {
                    score = 0.8f
                } else {
                    score = 0.6f
                }
            } else if (normLocTitle.isNotEmpty() && normSpotTitle.contains(normLocTitle)) {
                if (normSpotArtist == normLocArtist) {
                    score = 0.9f
                } else if (normSpotArtist.isNotEmpty() && normLocArtist.contains(normSpotArtist)) {
                    score = 0.8f
                } else {
                    score = 0.6f
                }
            }
            
            if (score > 0f) {
                val durationDelta = abs(spotifyTrack.durationMs - local.durationMs)
                if (durationDelta <= 5000) {
                    score = minOf(1.0f, score + 0.1f)
                }
                
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = local
                }
            }
        }
        
        val isMatched = bestScore >= 0.7f && bestMatch != null
        
        return SpotifyMatchResult(
            spotifyTitle = spotifyTrack.title,
            spotifyArtist = spotifyArtist,
            spotifyDurationMs = spotifyTrack.durationMs,
            spotifyCoverUrl = spotifyTrack.coverUrl,
            localTrackId = if (isMatched) bestMatch!!.id else null,
            localTitle = if (isMatched) bestMatch!!.title else null,
            localArtist = if (isMatched) bestMatch!!.artistName else null,
            confidence = if (isMatched) bestScore else 0f
        )
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("feat\\.?|ft\\.?"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace("ё", "е")
            .replace(Regex("[^a-zа-я0-9]"), "")
            .trim()
    }
}
