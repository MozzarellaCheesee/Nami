package dev.nami.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.domain.TrashRepository
import dev.nami.domain.TrashedAlbum
import dev.nami.domain.TrashedPlaylist
import dev.nami.domain.TrashedTrack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashUiState(
    val tracks: List<TrashedTrack> = emptyList(),
    val playlists: List<TrashedPlaylist> = emptyList(),
    val albums: List<TrashedAlbum> = emptyList(),
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
) : ViewModel() {

    val uiState: StateFlow<TrashUiState> = combine(
        trashRepository.trashedTracks(),
        trashRepository.trashedPlaylists(),
        trashRepository.trashedAlbums(),
    ) { tracks, playlists, albums -> TrashUiState(tracks, playlists, albums) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrashUiState())

    fun restoreTrack(id: TrackId) {
        viewModelScope.launch { trashRepository.restoreTrack(id) }
    }

    fun restorePlaylist(id: PlaylistId) {
        viewModelScope.launch { trashRepository.restorePlaylist(id) }
    }

    fun restoreAlbum(id: AlbumId) {
        viewModelScope.launch { trashRepository.restoreAlbum(id) }
    }

    fun deleteTrackForever(id: TrackId) {
        viewModelScope.launch { trashRepository.deleteTrackForever(id) }
    }

    fun deletePlaylistForever(id: PlaylistId) {
        viewModelScope.launch { trashRepository.deletePlaylistForever(id) }
    }

    fun deleteAlbumForever(id: AlbumId) {
        viewModelScope.launch { trashRepository.deleteAlbumForever(id) }
    }

    fun deleteAllForever() {
        viewModelScope.launch {
            val state = uiState.value
            state.tracks.forEach { trashRepository.deleteTrackForever(it.track.id) }
            state.playlists.forEach { trashRepository.deletePlaylistForever(it.playlist.id) }
            state.albums.forEach { trashRepository.deleteAlbumForever(it.album.id) }
        }
    }
}
