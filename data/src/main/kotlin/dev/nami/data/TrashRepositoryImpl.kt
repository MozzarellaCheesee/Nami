package dev.nami.data

import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.model.AlbumId
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.data.mapper.toTrashedDomain
import dev.nami.domain.TRASH_RETENTION_MS
import dev.nami.domain.TrashRepository
import dev.nami.domain.TrashedAlbum
import dev.nami.domain.TrashedPlaylist
import dev.nami.domain.TrashedTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TrashRepositoryImpl @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val albumDao: AlbumDao,
    private val trashFileStore: TrashFileStore,
) : TrashRepository {

    override fun trashedTracks(): Flow<List<TrashedTrack>> =
        trackDao.trashedTracksFlow().map { list -> list.map { it.toTrashedDomain() } }

    override fun trashedPlaylists(): Flow<List<TrashedPlaylist>> =
        playlistDao.trashedPlaylistsFlow().map { list -> list.map { it.toTrashedDomain() } }

    override fun trashedAlbums(): Flow<List<TrashedAlbum>> =
        albumDao.trashedAlbumsFlow().map { list -> list.map { it.toTrashedDomain() } }

    override suspend fun restoreTrack(id: TrackId) {
        val track = trackDao.findById(id.value) ?: return
        val restoredPath = trashFileStore.restoreFromMusic(id.value, track.path) ?: track.path
        trackDao.setDeletedAt(id.value, deletedAt = null, path = restoredPath)
    }

    override suspend fun restorePlaylist(id: PlaylistId) {
        playlistDao.restore(id.value)
    }

    override suspend fun restoreAlbum(id: AlbumId) {
        albumDao.restore(id.value)
        trackDao.trackIdsForAlbum(id.value).forEach { trackId ->
            if (trackDao.findById(trackId)?.deletedAt != null) restoreTrack(TrackId(trackId))
        }
    }

    override suspend fun deleteTrackForever(id: TrackId) {
        val track = trackDao.findById(id.value) ?: return
        trashFileStore.deletePermanently(track.path)
        trackDao.hardDelete(id.value)
    }

    override suspend fun deletePlaylistForever(id: PlaylistId) {
        playlistDao.hardDelete(id.value)
    }

    override suspend fun deleteAlbumForever(id: AlbumId) {
        trackDao.trackIdsForAlbum(id.value).forEach { deleteTrackForever(TrackId(it)) }
        albumDao.hardDelete(id.value)
    }

    override suspend fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
        trackDao.trashedTracksFlow().first()
            .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            .forEach { deleteTrackForever(TrackId(it.id)) }
        playlistDao.trashedPlaylistsFlow().first()
            .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            .forEach { deletePlaylistForever(PlaylistId(it.id)) }
        albumDao.trashedAlbumsFlow().first()
            .filter { it.deletedAt < cutoff }
            .forEach { deleteAlbumForever(AlbumId(it.id)) }
    }
}
