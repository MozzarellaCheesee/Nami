package dev.nami.domain

import androidx.paging.PagingData
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun playlists(): Flow<PagingData<PlaylistSummary>>
    fun playlist(id: PlaylistId): Flow<Playlist?>
    fun tracksInPlaylist(id: PlaylistId): Flow<List<Track>>
    suspend fun createPlaylist(name: String): PlaylistId
    suspend fun renamePlaylist(id: PlaylistId, name: String)
    suspend fun deletePlaylist(id: PlaylistId)
    suspend fun setCoverImage(id: PlaylistId, imageUri: String)
    suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId)
    suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId)
    suspend fun exportM3u8(id: PlaylistId, destinationUri: String)
    suspend fun importM3u8(sourceUri: String, playlistName: String): ImportM3u8Result
}

data class ImportM3u8Result(val playlistId: PlaylistId, val matchedCount: Int, val skippedCount: Int)
