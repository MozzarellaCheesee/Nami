package dev.nami.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Loads both tracks for [ABCompareScreen] by id - the nav route only carries two ids, not full
 * Track objects. */
@HiltViewModel
class ABCompareEntryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val tracks: StateFlow<Pair<Track?, Track?>> = combine(
        libraryRepository.track(TrackId(checkNotNull(savedStateHandle["trackIdA"]))),
        libraryRepository.track(TrackId(checkNotNull(savedStateHandle["trackIdB"]))),
    ) { a, b -> a to b }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null to null,
    )
}
