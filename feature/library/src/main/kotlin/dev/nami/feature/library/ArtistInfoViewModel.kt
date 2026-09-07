package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistInfoStats(val albumCount: Int, val trackCount: Int, val totalDurationMs: Long)

/** Artist counterpart of TrackInfoViewModel/AlbumInfoViewModel -- name is the only real editable
 * field an Artist row has (photo already has its own picker flow on ArtistDetailScreen). */
@HiltViewModel
class ArtistInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val artistId = ArtistId(requireNotNull(savedStateHandle.get<String>("artistId")))

    val artist: StateFlow<Artist?> = libraryRepository.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<ArtistInfoStats> = combine(
        libraryRepository.albumsByArtist(artistId),
        libraryRepository.tracksByArtist(artistId),
    ) { albums, tracks -> ArtistInfoStats(albums.size, tracks.size, tracks.sumOf { it.durationMs }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistInfoStats(0, 0, 0))

    fun rename(name: String) {
        viewModelScope.launch { libraryRepository.renameArtist(artistId, name) }
    }
}
