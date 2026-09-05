package dev.nami.data

import android.content.Context
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map as pagingMap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.database.entity.PlaylistTrackEntity
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.data.mapper.toDomain
import dev.nami.domain.ImportM3u8Result
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val trackDao: TrackDao,
    private val artworkStore: ArtworkStore,
) : PlaylistRepository {

    override fun playlists(): Flow<PagingData<PlaylistSummary>> =
        Pager(PagingConfig(pageSize = 30)) { playlistDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun playlist(id: PlaylistId): Flow<Playlist?> =
        playlistDao.findByIdFlow(id.value).map { it?.toDomain() }

    override fun tracksInPlaylist(id: PlaylistId): Flow<List<Track>> =
        playlistTrackDao.tracksInPlaylistFlow(id.value).map { list -> list.map { it.toDomain() } }

    override suspend fun createPlaylist(name: String): PlaylistId {
        val id = UUID.randomUUID().toString()
        playlistDao.insert(PlaylistEntity(id = id, name = name, coverPath = null, createdAt = System.currentTimeMillis()))
        return PlaylistId(id)
    }

    override suspend fun renamePlaylist(id: PlaylistId, name: String) {
        playlistDao.rename(id.value, name)
    }

    override suspend fun deletePlaylist(id: PlaylistId) {
        playlistDao.softDelete(id.value, deletedAt = System.currentTimeMillis())
    }

    override suspend fun setCoverImage(id: PlaylistId, imageUri: String) {
        val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
        artworkStore.save(id.value, bytes)?.let { path -> playlistDao.setCoverPath(id.value, path) }
    }

    override suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId) {
        val position = playlistTrackDao.nextPosition(playlistId.value)
        playlistTrackDao.insert(
            PlaylistTrackEntity(
                playlistId = playlistId.value,
                trackId = trackId.value,
                position = position,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId) {
        playlistTrackDao.remove(playlistId.value, trackId.value)
    }

    override suspend fun exportM3u8(id: PlaylistId, destinationUri: String) {
        val paths = playlistTrackDao.tracksInPlaylist(id.value).map { it.path }
        val content = "#EXTM3U\n" + paths.joinToString("\n")
        context.contentResolver.openOutputStream(destinationUri.toUri())?.use { output ->
            output.write(content.toByteArray())
        }
    }

    override suspend fun importM3u8(sourceUri: String, playlistName: String): ImportM3u8Result {
        val lines = context.contentResolver.openInputStream(sourceUri.toUri())?.use { it.bufferedReader().readLines() }
            ?: emptyList()
        val candidatePaths = lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

        val playlistId = createPlaylist(playlistName)
        var matched = 0
        var skipped = 0
        for (path in candidatePaths) {
            val track = trackDao.findByPath(path)
            if (track != null) {
                addTrack(playlistId, TrackId(track.id))
                matched++
            } else {
                skipped++
            }
        }
        return ImportM3u8Result(playlistId, matched, skipped)
    }
}
