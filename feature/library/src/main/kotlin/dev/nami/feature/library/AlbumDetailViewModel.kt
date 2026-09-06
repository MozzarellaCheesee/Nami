package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val albumId = AlbumId(checkNotNull(savedStateHandle.get<String>("albumId")))

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    val nowPlaying: StateFlow<NowPlayingRow?> = playerRepository.state
        .map { state -> (state as? PlaybackState.Playing)?.let { NowPlayingRow(it.trackId, it.isPlaying) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        combine(
            libraryRepository.album(albumId),
            libraryRepository.tracksInAlbum(albumId),
            libraryRepository.albumArtists(albumId),
        ) { album, tracks, artists ->
            AlbumDetailUiState(album = album, tracks = tracks, artists = artists)
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

    fun setYear(year: Int?) {
        viewModelScope.launch { libraryRepository.setAlbumYear(albumId, year) }
    }

    fun setArtist(artistId: ArtistId?) {
        viewModelScope.launch { libraryRepository.setAlbumArtist(albumId, artistId) }
    }

    fun addArtist(artistId: ArtistId) {
        viewModelScope.launch { libraryRepository.addAlbumArtist(albumId, artistId) }
    }

    fun removeArtist(artistId: ArtistId) {
        viewModelScope.launch { libraryRepository.removeAlbumArtist(albumId, artistId) }
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
