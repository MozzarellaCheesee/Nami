package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailUiState(
    val artist: Artist? = null,
    val albums: List<AlbumSummary> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val artistId = ArtistId(checkNotNull(savedStateHandle.get<String>("artistId")))

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    init {
        combine(
            libraryRepository.artist(artistId),
            libraryRepository.albumsByArtist(artistId),
            libraryRepository.tracksByArtist(artistId),
        ) { artist, albums, tracks -> ArtistDetailUiState(artist, albums, tracks) }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun renameArtist(name: String) {
        viewModelScope.launch { libraryRepository.renameArtist(artistId, name) }
    }
}
