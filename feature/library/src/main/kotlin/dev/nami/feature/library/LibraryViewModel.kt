package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.Track
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { TRACKS, ALBUMS, ARTISTS }

data class LibraryUiState(
    val importProgress: ImportProgress? = null,
    val selectedTab: LibraryTab = LibraryTab.TRACKS,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    val tracks: Flow<PagingData<Track>> =
        libraryRepository.tracks().cachedIn(viewModelScope)

    val albums: Flow<PagingData<AlbumSummary>> =
        libraryRepository.albums().cachedIn(viewModelScope)

    val artists: Flow<PagingData<Artist>> =
        libraryRepository.artists().cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun selectTab(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun importFiles(uris: List<String>) {
        viewModelScope.launch {
            libraryRepository.import(ImportSource.Files(uris)).collect { progress ->
                _uiState.value = _uiState.value.copy(importProgress = progress)
            }
            searchRepository.rebuildIndex()
        }
    }
}
