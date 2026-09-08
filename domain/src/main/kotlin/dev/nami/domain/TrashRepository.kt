package dev.nami.domain

import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

data class TrashedTrack(val track: Track, val deletedAt: Long)
data class TrashedPlaylist(val playlist: PlaylistSummary, val deletedAt: Long)
data class TrashedAlbum(val album: AlbumSummary, val deletedAt: Long)

interface TrashRepository {
    fun trashedTracks(): Flow<List<TrashedTrack>>
    fun trashedPlaylists(): Flow<List<TrashedPlaylist>>
    fun trashedAlbums(): Flow<List<TrashedAlbum>>
    suspend fun restoreTrack(id: TrackId)
    suspend fun restorePlaylist(id: PlaylistId)
    /** Also restores every track of the album currently in trash - an album and its tracks are
     * trashed together as one action (see LibraryRepository.deleteAlbum), so restoring it undoes
     * the whole thing rather than leaving an "empty" restored album with no playable tracks. */
    suspend fun restoreAlbum(id: AlbumId)
    suspend fun deleteTrackForever(id: TrackId)
    suspend fun deletePlaylistForever(id: PlaylistId)
    suspend fun deleteAlbumForever(id: AlbumId)
    suspend fun purgeExpired()
}
