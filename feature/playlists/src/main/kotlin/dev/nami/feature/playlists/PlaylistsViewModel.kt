package dev.nami.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistSummary
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val playlists: Flow<PagingData<PlaylistSummary>> =
        playlistRepository.playlists().cachedIn(viewModelScope)

    fun createPlaylist(name: String) {
        viewModelScope.launch { playlistRepository.createPlaylist(name) }
    }
}
