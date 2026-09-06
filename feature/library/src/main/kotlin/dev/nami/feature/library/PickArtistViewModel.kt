package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Artist
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class PickArtistViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val artists: Flow<PagingData<Artist>> = libraryRepository.artists().cachedIn(viewModelScope)
}
