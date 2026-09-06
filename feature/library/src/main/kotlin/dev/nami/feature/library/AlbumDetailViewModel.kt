package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(val album: Album? = null, val tracks: List<Track> = emptyList())

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val albumId = AlbumId(checkNotNull(savedStateHandle.get<String>("albumId")))

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        libraryRepository.album(albumId)
            .combine(libraryRepository.tracksInAlbum(albumId)) { album, tracks ->
                AlbumDetailUiState(album = album, tracks = tracks)
            }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun renameAlbum(title: String) {
        viewModelScope.launch { libraryRepository.renameAlbum(albumId, title) }
    }

    fun setIsSingle(isSingle: Boolean) {
        viewModelScope.launch { libraryRepository.setAlbumIsSingle(albumId, isSingle) }
    }

    fun removeTrackFromAlbum(trackId: TrackId) {
        viewModelScope.launch { libraryRepository.removeTrackFromAlbum(trackId) }
    }

    fun deleteAlbum() {
        viewModelScope.launch {
            libraryRepository.deleteTracks(_uiState.value.tracks.map { it.id })
        }
    }
}
