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
import dev.nami.domain.SmartQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

private const val LIKED_PLAYLIST_NAME = "Любимые треки"

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

    override suspend fun recentPlaylists(limit: Int): List<PlaylistSummary> =
        playlistDao.recent(limit).map { it.toDomain() }

    override fun playlist(id: PlaylistId): Flow<Playlist?> =
        playlistDao.findByIdFlow(id.value).map { it?.toDomain() }

    override fun tracksInPlaylist(id: PlaylistId): Flow<List<Track>> = flow {
        val playlist = playlistDao.findById(id.value)
        if (playlist?.isSmart == true) {
            // Re-evaluated fresh on every collection (each screen open), not cached - see
            // PlaylistRepository.createSmartPlaylist's own doc.
            val query = playlist.smartQueryJson?.let(SmartQuerySerializer::parse)
            val allTracks = trackDao.allOrderedWithArtwork().map { it.toDomain() }
            emit(query?.let { SmartPlaylistEvaluator.evaluate(allTracks, it) } ?: emptyList())
        } else {
            emitAll(playlistTrackDao.tracksInPlaylistFlow(id.value).map { list -> list.map { it.toDomain() } })
        }
    }

    override suspend fun createPlaylist(name: String): PlaylistId {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        playlistDao.insert(PlaylistEntity(id = id, name = name, coverPath = null, createdAt = now, updatedAt = now))
        return PlaylistId(id)
    }

    // Rename/delete/cover all guarded server-side too, not just hidden in the UI - the Liked
    // playlist's name and heart cover are fixed and it can't be trashed, matching Spotify's own
    // Liked Songs. Silent no-ops (fail closed) rather than throwing: the UI is expected to never
    // offer these actions for it in the first place, so reaching here at all means something
    // upstream didn't check - not worth crashing over.
    override suspend fun renamePlaylist(id: PlaylistId, name: String) {
        if (playlistDao.findById(id.value)?.isLiked == true) return
        playlistDao.rename(id.value, name)
    }

    override suspend fun deletePlaylist(id: PlaylistId) {
        if (playlistDao.findById(id.value)?.isLiked == true) return
        playlistDao.softDelete(id.value, deletedAt = System.currentTimeMillis())
    }

    override suspend fun setCoverImage(id: PlaylistId, imageUri: String) {
        if (playlistDao.findById(id.value)?.isLiked == true) return
        val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
        artworkStore.save(id.value, bytes)?.let { path -> playlistDao.setCoverPath(id.value, path) }
    }

    override suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId) {
        SyncTombstones.cancel(context, "playlist_track", "${playlistId.value}:${trackId.value}")
        val position = playlistTrackDao.nextPosition(playlistId.value)
        playlistTrackDao.insert(
            PlaylistTrackEntity(
                playlistId = playlistId.value,
                trackId = trackId.value,
                position = position,
                addedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId) {
        SyncTombstones.add(context, "playlist_track", "${playlistId.value}:${trackId.value}")
        playlistTrackDao.remove(playlistId.value, trackId.value)
    }

    override suspend fun exportM3u8(id: PlaylistId, destinationUri: String) {
        val paths = playlistTrackDao.tracksInPlaylist(id.value).map { it.path }
        val content = "#EXTM3U\n" + paths.joinToString("\n")
        context.contentResolver.openOutputStream(destinationUri.toUri())?.use { output ->
            output.write(content.toByteArray())
        }
    }

    override fun isTrackLiked(trackId: TrackId): Flow<Boolean> =
        playlistTrackDao.isTrackInLikedPlaylistFlow(trackId.value)

    override suspend fun toggleLike(trackId: TrackId): Boolean {
        val likedPlaylistId = ensureLikedPlaylist()
        val alreadyLiked = playlistTrackDao.isTrackInLikedPlaylistFlow(trackId.value).first()
        if (alreadyLiked) {
            removeTrack(likedPlaylistId, trackId)
        } else {
            addTrack(likedPlaylistId, trackId)
        }
        // Лайк - единственное состояние виджета, которое не проходит через плеер: без явного
        // пинка сердечко на рабочем столе оставалось старым до следующей смены трека.
        dev.nami.player.nudgeNamiWidgets(context)
        return !alreadyLiked
    }

    override suspend fun likeTrack(trackId: TrackId) {
        addTrack(ensureLikedPlaylist(), trackId)
    }

    override suspend fun createSmartPlaylist(name: String, query: SmartQuery): PlaylistId {
        val id = UUID.randomUUID().toString()
        playlistDao.insert(
            PlaylistEntity(
                id = id,
                name = name,
                coverPath = null,
                createdAt = System.currentTimeMillis(),
                isSmart = true,
                smartQueryJson = SmartQuerySerializer.serialize(query),
            ),
        )
        return PlaylistId(id)
    }

    override suspend fun setPlaybackSettings(
        id: PlaylistId,
        eqGainsCsv: String?,
        crossfadeEnabled: Boolean?,
        shuffleOnStart: Boolean?,
    ) {
        playlistDao.updatePlaybackSettings(id.value, eqGainsCsv, crossfadeEnabled, shuffleOnStart)
    }

    override suspend fun updateSmartQuery(id: PlaylistId, query: SmartQuery) {
        playlistDao.updateSmartQuery(id.value, SmartQuerySerializer.serialize(query))
    }

    override fun parseSmartQuery(json: String): SmartQuery? = SmartQuerySerializer.parse(json)

    /** Finds the one Liked playlist, creating it (with its own fixed name - see [LIKED_PLAYLIST_NAME])
     * the first time anything is ever liked. Idempotent: a second call while one already exists
     * just returns its id. */
    private suspend fun ensureLikedPlaylist(): PlaylistId {
        playlistDao.findLikedPlaylist()?.let { return PlaylistId(it.id) }
        val id = UUID.randomUUID().toString()
        playlistDao.insert(
            PlaylistEntity(
                id = id,
                name = LIKED_PLAYLIST_NAME,
                coverPath = null,
                createdAt = System.currentTimeMillis(),
                isLiked = true,
            ),
        )
        return PlaylistId(id)
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
