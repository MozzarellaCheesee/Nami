package dev.nami.feature.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(val playlist: Playlist? = null, val tracks: List<Track> = emptyList())

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val playlistId = PlaylistId(checkNotNull(savedStateHandle.get<String>("playlistId")))

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    init {
        playlistRepository.playlist(playlistId)
            .combine(playlistRepository.tracksInPlaylist(playlistId)) { playlist, tracks ->
                PlaylistDetailUiState(playlist = playlist, tracks = tracks)
            }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun removeTrack(trackId: TrackId) {
        viewModelScope.launch { playlistRepository.removeTrack(playlistId, trackId) }
    }

    fun rename(name: String) {
        viewModelScope.launch { playlistRepository.renamePlaylist(playlistId, name) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
            onDeleted()
        }
    }
}
