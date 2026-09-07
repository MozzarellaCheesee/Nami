package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
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

data class ArtistDetailUiState(
    val artist: Artist? = null,
    val albums: List<AlbumSummary> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
    private val playlistRepository: PlaylistRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val artistId = ArtistId(checkNotNull(savedStateHandle.get<String>("artistId")))

    fun likeTrack(trackId: TrackId) {
        viewModelScope.launch { playlistRepository.likeTrack(trackId) }
    }

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    val nowPlaying: StateFlow<NowPlayingRow?> = playerRepository.state
        .map { state -> (state as? PlaybackState.Playing)?.let { NowPlayingRow(it.trackId, it.isPlaying) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun removeTrackFromArtist(trackId: TrackId) {
        viewModelScope.launch { libraryRepository.removeTrackFromArtist(trackId) }
    }

    fun batchEditTracks(ids: List<TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) {
        viewModelScope.launch { libraryRepository.batchEditTracks(ids, artistName, albumName, year, genre) }
    }

    suspend fun searchMusicBrainz(title: String, artistName: String?) =
        libraryRepository.searchMusicBrainz(title, artistName)
}
