package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.TrashRepository
import kotlinx.coroutines.CancellationException
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
    val lastDeletedTrackId: TrackId? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
    private val trashRepository: TrashRepository,
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
            try {
                libraryRepository.import(ImportSource.Files(uris)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial import failure: full error handling/reporting is a later task.
                // Swallow so viewModelScope survives and rebuildIndex still runs below.
            } finally {
                searchRepository.rebuildIndex()
            }
        }
    }

    fun deleteTrack(id: TrackId) {
        viewModelScope.launch {
            libraryRepository.deleteTrack(id)
            _uiState.value = _uiState.value.copy(lastDeletedTrackId = id)
        }
    }

    fun undoLastDelete() {
        val id = _uiState.value.lastDeletedTrackId ?: return
        viewModelScope.launch {
            trashRepository.restoreTrack(id)
            _uiState.value = _uiState.value.copy(lastDeletedTrackId = null)
        }
    }

    fun dismissDeleteSnackbar() {
        _uiState.value = _uiState.value.copy(lastDeletedTrackId = null)
    }
}
