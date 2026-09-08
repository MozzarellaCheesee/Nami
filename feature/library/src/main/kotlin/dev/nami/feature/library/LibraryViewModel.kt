package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.TrashRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { TRACKS, ALBUMS, ARTISTS }

data class NowPlayingRow(val trackId: TrackId, val isPlaying: Boolean)

data class LibraryUiState(
    val importProgress: ImportProgress? = null,
    val selectedTab: LibraryTab = LibraryTab.TRACKS,
    val lastDeletedTrackIds: Set<TrackId> = emptySet(),
    val selectedTrackIds: Set<TrackId> = emptySet(),
    val selectedAlbumIds: Set<AlbumId> = emptySet(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
    private val trashRepository: TrashRepository,
    private val playerRepository: PlayerRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    fun likeTrack(trackId: TrackId) {
        viewModelScope.launch { playlistRepository.likeTrack(trackId) }
    }

    fun likeSelectedTracks() {
        val ids = uiState.value.selectedTrackIds
        viewModelScope.launch { ids.forEach { playlistRepository.likeTrack(it) } }
        clearSelection()
    }

    fun batchEditSelectedTracks(artistName: String?, albumName: String?, year: Int?, genre: String?) {
        val ids = uiState.value.selectedTrackIds.toList()
        clearSelection()
        batchEditTracks(ids, artistName, albumName, year, genre)
    }

    /** Same TagEditDialog, single track from a row's own "⋮" menu -- [batchEditSelectedTracks]
     * is just this called with the current selection. */
    fun batchEditTracks(ids: List<TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) {
        viewModelScope.launch { libraryRepository.batchEditTracks(ids, artistName, albumName, year, genre) }
    }

    suspend fun searchMusicBrainz(title: String, artistName: String?) =
        libraryRepository.searchMusicBrainz(title, artistName)

    val tracks: Flow<PagingData<Track>> =
        libraryRepository.tracks().cachedIn(viewModelScope)

    val albums: Flow<PagingData<AlbumSummary>> =
        libraryRepository.albums().cachedIn(viewModelScope)

    val artists: Flow<PagingData<Artist>> =
        libraryRepository.artists().cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Live -- any rename/cover/artist/track change to any album updates this without needing a
    // manual refresh call (recentAlbums() is now a Room-backed Flow, not a one-shot snapshot).
    val recentAlbums: StateFlow<List<AlbumSummary>> = libraryRepository.recentAlbums(limit = 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val featuredArtists: StateFlow<List<Artist>> = libraryRepository.featuredArtists(limit = 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nowPlaying: StateFlow<NowPlayingRow?> = playerRepository.state
        .map { state -> (state as? PlaybackState.Playing)?.let { NowPlayingRow(it.trackId, it.isPlaying) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectTab(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerRepository.addToQueue(
                dev.nami.domain.PlayableTrack(
                    id = track.id,
                    title = track.title,
                    artistName = track.artistName,
                    path = track.path,
                    artworkPath = track.albumArtworkPath,
                    format = track.format,
                    cueStartMs = track.cueStartMs,
                    cueEndMs = track.cueEndMs,
                ),
            )
        }
    }

    fun importFiles(uris: List<String>) {
        viewModelScope.launch {
            try {
                libraryRepository.import(ImportSource.Files(uris)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial import failure: full error handling/reporting is a later task.
                // Swallow so viewModelScope survives and rebuildIndex still runs below.
            } finally {
                searchRepository.rebuildIndex()
                _uiState.value = _uiState.value.copy(importProgress = null)
            }
        }
    }

    fun importFolder(treeUri: String) {
        viewModelScope.launch {
            try {
                libraryRepository.import(ImportSource.Folder(treeUri)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial import failure: full error handling/reporting is a later task.
                // Swallow so viewModelScope survives and rebuildIndex still runs below.
            } finally {
                searchRepository.rebuildIndex()
                _uiState.value = _uiState.value.copy(importProgress = null)
            }
        }
    }

    /** П.md §2 "Режим наблюдения за папкой" -- reruns folder import for every remembered SAF
     * tree, one at a time. No true background watch exists for SAF trees on Android, so this is
     * called on cold start and from a manual "Обновить" action instead of ever running silently
     * in the background. */
    fun rescanWatchedFolders() {
        val folders = settingsRepository.watchedFolders.value
        if (folders.isEmpty()) return
        viewModelScope.launch {
            for (treeUri in folders) {
                try {
                    libraryRepository.import(ImportSource.Folder(treeUri)).collect { progress ->
                        _uiState.value = _uiState.value.copy(importProgress = progress)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One watched folder failing (permission revoked, tree gone) shouldn't stop
                    // the rest from rescanning.
                }
            }
            searchRepository.rebuildIndex()
            _uiState.value = _uiState.value.copy(importProgress = null)
        }
    }

    fun addWatchedFolder(treeUri: String) {
        settingsRepository.addWatchedFolder(treeUri)
        importFolder(treeUri)
    }

    fun removeWatchedFolder(treeUri: String) {
        settingsRepository.removeWatchedFolder(treeUri)
    }

    val watchedFolders: StateFlow<List<String>> = settingsRepository.watchedFolders

    fun importZip(uri: String) {
        viewModelScope.launch {
            try {
                libraryRepository.import(ImportSource.Zip(uri)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial import failure: full error handling/reporting is a later task.
                // Swallow so viewModelScope survives and rebuildIndex still runs below.
            } finally {
                searchRepository.rebuildIndex()
                _uiState.value = _uiState.value.copy(importProgress = null)
            }
        }
    }

    fun deleteTrack(id: TrackId) {
        deleteTracks(setOf(id))
    }

    fun toggleTrackSelection(id: TrackId) {
        val current = _uiState.value.selectedTrackIds
        _uiState.value = _uiState.value.copy(
            selectedTrackIds = if (id in current) current - id else current + id,
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedTrackIds = emptySet())
    }

    /** Replaces the whole selection -- used by drag-select and "select all". */
    fun setSelectedTracks(ids: Set<TrackId>) {
        _uiState.value = _uiState.value.copy(selectedTrackIds = ids)
    }

    fun deleteSelectedTracks() {
        val ids = _uiState.value.selectedTrackIds
        if (ids.isEmpty()) return
        deleteTracks(ids)
        clearSelection()
    }

    private fun deleteTracks(ids: Set<TrackId>) {
        viewModelScope.launch {
            libraryRepository.deleteTracks(ids.toList())
            playerRepository.removeTracks(ids)
            _uiState.value = _uiState.value.copy(lastDeletedTrackIds = ids)
        }
    }

    fun undoLastDelete() {
        val ids = _uiState.value.lastDeletedTrackIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { trashRepository.restoreTrack(it) }
            _uiState.value = _uiState.value.copy(lastDeletedTrackIds = emptySet())
        }
    }

    fun dismissDeleteSnackbar() {
        _uiState.value = _uiState.value.copy(lastDeletedTrackIds = emptySet())
    }

    fun renameTrack(id: TrackId, title: String) {
        viewModelScope.launch { libraryRepository.renameTrack(id, title) }
    }

    fun setTrackNote(id: TrackId, note: String?) {
        viewModelScope.launch { libraryRepository.setTrackNote(id, note) }
    }

    fun toggleAlbumSelection(id: AlbumId) {
        val current = _uiState.value.selectedAlbumIds
        _uiState.value = _uiState.value.copy(
            selectedAlbumIds = if (id in current) current - id else current + id,
        )
    }

    fun clearAlbumSelection() {
        _uiState.value = _uiState.value.copy(selectedAlbumIds = emptySet())
    }

    /** New empty album, then hands its id back so the caller can navigate straight to its detail
     * screen -- that screen already does everything a "create album" flow needs (rename, cover,
     * single/album toggle, add/remove tracks), so there's no separate composer screen. */
    fun createAlbum(onCreated: (AlbumId) -> Unit) {
        viewModelScope.launch {
            val id = libraryRepository.createAlbum(title = "Новый альбом", artistId = null)
            onCreated(id)
        }
    }

    fun deleteSelectedAlbums() {
        val ids = _uiState.value.selectedAlbumIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { albumId ->
                val trackIds = libraryRepository.tracksInAlbum(albumId).first().map { it.id }
                libraryRepository.deleteTracks(trackIds)
            }
            clearAlbumSelection()
        }
    }
}
