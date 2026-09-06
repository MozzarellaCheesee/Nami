package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddTracksToAlbumViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    // Already-in-this-album tracks would just be a no-op tap and clutter the list -- filtered out
    // per screen (albumId isn't known until the composable passes it to addTrack), so this simply
    // exposes every track and AddTracksToAlbumDialog filters against the current albumId.
    val tracks: Flow<PagingData<Track>> =
        libraryRepository.tracks().cachedIn(viewModelScope)

    fun addTrack(trackId: TrackId, albumId: AlbumId) {
        viewModelScope.launch { libraryRepository.addTrackToAlbum(trackId, albumId) }
    }
}
