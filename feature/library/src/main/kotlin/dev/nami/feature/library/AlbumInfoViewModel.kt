package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumInfoStats(val trackCount: Int, val totalDurationMs: Long)

/** Album counterpart of TrackInfoViewModel -- same "each field edits itself, saves immediately"
 * shape, backed by the same LibraryRepository methods AlbumDetailScreen's own menu already uses
 * (renameAlbum/setAlbumYear/setAlbumIsSingle). */
@HiltViewModel
class AlbumInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val albumId = AlbumId(requireNotNull(savedStateHandle.get<String>("albumId")))

    val album: StateFlow<Album?> = libraryRepository.album(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artists: StateFlow<List<dev.nami.core.model.Artist>> = libraryRepository.albumArtists(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<AlbumInfoStats> = libraryRepository.tracksInAlbum(albumId)
        .map { tracks -> AlbumInfoStats(tracks.size, tracks.sumOf { it.durationMs }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumInfoStats(0, 0))

    fun rename(title: String) {
        viewModelScope.launch { libraryRepository.renameAlbum(albumId, title) }
    }

    fun setYear(year: Int?) {
        viewModelScope.launch { libraryRepository.setAlbumYear(albumId, year) }
    }

    fun setIsSingle(isSingle: Boolean) {
        viewModelScope.launch { libraryRepository.setAlbumIsSingle(albumId, isSingle) }
    }
}
