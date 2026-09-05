package dev.nami.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val playlists: Flow<PagingData<PlaylistSummary>> =
        playlistRepository.playlists().cachedIn(viewModelScope)

    fun addToExistingPlaylist(playlistId: PlaylistId, trackIds: Set<TrackId>) {
        viewModelScope.launch {
            trackIds.forEach { playlistRepository.addTrack(playlistId, it) }
        }
    }

    fun addToNewPlaylist(name: String, trackIds: Set<TrackId>) {
        viewModelScope.launch {
            val playlistId = playlistRepository.createPlaylist(name)
            trackIds.forEach { playlistRepository.addTrack(playlistId, it) }
        }
    }
}
