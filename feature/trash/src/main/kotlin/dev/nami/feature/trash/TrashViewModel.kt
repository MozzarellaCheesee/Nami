package dev.nami.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.domain.TrashRepository
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
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
) : ViewModel() {

    val uiState: StateFlow<TrashUiState> = combine(
        trashRepository.trashedTracks(),
        trashRepository.trashedPlaylists(),
    ) { tracks, playlists -> TrashUiState(tracks, playlists) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrashUiState())

    fun restoreTrack(id: TrackId) {
        viewModelScope.launch { trashRepository.restoreTrack(id) }
    }

    fun restorePlaylist(id: PlaylistId) {
        viewModelScope.launch { trashRepository.restorePlaylist(id) }
    }

    fun deleteTrackForever(id: TrackId) {
        viewModelScope.launch { trashRepository.deleteTrackForever(id) }
    }

    fun deletePlaylistForever(id: PlaylistId) {
        viewModelScope.launch { trashRepository.deletePlaylistForever(id) }
    }
}
