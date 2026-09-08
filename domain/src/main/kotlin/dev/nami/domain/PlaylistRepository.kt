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
    /** Небольшой снимок последних плейлистов для блока главного экрана - Pager там не нужен,
     * запрос тот же, что у [playlists], только с LIMIT (ср. LibraryRepository.recentAlbums). */
    suspend fun recentPlaylists(limit: Int): List<PlaylistSummary>
    fun playlist(id: PlaylistId): Flow<Playlist?>
    fun tracksInPlaylist(id: PlaylistId): Flow<List<Track>>
    suspend fun createPlaylist(name: String): PlaylistId
    suspend fun renamePlaylist(id: PlaylistId, name: String)
    /** Soft-deletes: the playlist moves to trash for 30 days (see TrashRepository), not removed immediately. */
    suspend fun deletePlaylist(id: PlaylistId)
    suspend fun setCoverImage(id: PlaylistId, imageUri: String)
    suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId)
    suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId)
    suspend fun exportM3u8(id: PlaylistId, destinationUri: String)
    suspend fun importM3u8(sourceUri: String, playlistName: String): ImportM3u8Result

    /** Live "is this track in the Любимые треки playlist right now" - for a heart icon anywhere
     * a track is shown. False (never a loading state) if the Liked playlist doesn't exist yet,
     * i.e. nothing has ever been liked. */
    fun isTrackLiked(trackId: TrackId): Flow<Boolean>

    /** Adds/removes [trackId] from the Любимые треки playlist, creating it on first use (lazily,
     * not at app install) - returns the new liked state. Duplicate-safe: the underlying
     * playlist_tracks row is keyed by (playlistId, trackId), so liking an already-liked track is
     * a no-op on the add path, same guarantee every other "add to playlist" flow already has. */
    suspend fun toggleLike(trackId: TrackId): Boolean

    /** Add-only version of [toggleLike] for a plain "В любимые" menu action (as opposed to the
     * player's heart, which toggles) - no-op if already liked. */
    suspend fun likeTrack(trackId: TrackId)

    /** П.md §20 "умные плейлисты" - [tracksInPlaylist] evaluates [query] fresh every time it's
     * collected for one of these (not cached), so opening a smart playlist always reflects the
     * current library, per the plan's own "даёт актуальный на момент открытия список" wording. */
    suspend fun createSmartPlaylist(name: String, query: SmartQuery): PlaylistId
    suspend fun updateSmartQuery(id: PlaylistId, query: SmartQuery)

    /** The other direction of [Playlist.smartQueryJson] - parses it back into a [SmartQuery] for
     * an editor screen to load. Null on a missing/corrupted value, same as a fresh, ruleless query. */
    fun parseSmartQuery(json: String): SmartQuery?
}

data class ImportM3u8Result(val playlistId: PlaylistId, val matchedCount: Int, val skippedCount: Int)
