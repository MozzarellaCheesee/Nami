package dev.nami.domain

import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

data class TrashedTrack(val track: Track, val deletedAt: Long)
data class TrashedPlaylist(val playlist: PlaylistSummary, val deletedAt: Long)

interface TrashRepository {
    fun trashedTracks(): Flow<List<TrashedTrack>>
    fun trashedPlaylists(): Flow<List<TrashedPlaylist>>
    suspend fun restoreTrack(id: TrackId)
    suspend fun restorePlaylist(id: PlaylistId)
    suspend fun deleteTrackForever(id: TrackId)
    suspend fun deletePlaylistForever(id: PlaylistId)
    suspend fun purgeExpired()
}
