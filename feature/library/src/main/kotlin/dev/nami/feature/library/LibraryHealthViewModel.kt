package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryHealthReport
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryHealthViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _report = MutableStateFlow<LibraryHealthReport?>(null)
    val report: StateFlow<LibraryHealthReport?> = _report.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _report.value = libraryRepository.libraryHealthReport()
            _isLoading.value = false
        }
    }

    /** Missing-file rows are just soft-deleted like any other delete -- TrashFileStore's own
     * moveToTrash/deletePermanently already no-op cleanly when the source file isn't there. */
    fun deleteTrack(id: TrackId) {
        viewModelScope.launch {
            libraryRepository.deleteTrack(id)
            refresh()
        }
    }

    /** Keeps [keepId], deletes every other track in the same duplicate group. */
    fun resolveDuplicateGroup(keepId: TrackId, group: List<TrackId>) {
        viewModelScope.launch {
            group.filterNot { it == keepId }.forEach { libraryRepository.deleteTrack(it) }
            refresh()
        }
    }
}
