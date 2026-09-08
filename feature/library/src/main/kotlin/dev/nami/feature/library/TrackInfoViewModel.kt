package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Album
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.Tag
import dev.nami.domain.TagId
import dev.nami.domain.TagRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** А5 - "отдельное окно информации о треке" (План.md): every known field, edited in place via
 * the small dialogs in TrackInfoScreen - no separate "edit mode", each row's own dialog saves
 * immediately, same as every other one-field-at-a-time editor in this app (RenameDialog etc).
 * "Начать радио"/"Поделиться карточкой" переехали в общее меню "ещё" - см.
 * TrackQuickActionsViewModel, не здесь. */
@HiltViewModel
class TrackInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val tagRepository: TagRepository,
) : ViewModel() {

    private val trackId = TrackId(requireNotNull(savedStateHandle.get<String>("trackId")))

    val trackTags: StateFlow<List<Tag>> = tagRepository.tagsForTrack(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<Tag>> = tagRepository.tags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createAndAssignTag(name: String, colorArgb: Int) {
        viewModelScope.launch {
            val id = tagRepository.createTag(name, colorArgb)
            tagRepository.assignTag(trackId, id)
        }
    }

    fun assignTag(tagId: TagId) {
        viewModelScope.launch { tagRepository.assignTag(trackId, tagId) }
    }

    fun removeTag(tagId: TagId) {
        viewModelScope.launch { tagRepository.unassignTag(trackId, tagId) }
    }

    val track: StateFlow<Track?> = libraryRepository.track(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Track carries artistName but not an album title/year - those live on the Album row, so a
    // second reactive lookup keyed off the track's current albumId (re-subscribes if the track
    // gets moved to a different album).
    val album: StateFlow<Album?> = track
        .flatMapLatest { t -> t?.albumId?.let { libraryRepository.album(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun renameTrack(title: String) {
        viewModelScope.launch { libraryRepository.renameTrack(trackId, title) }
    }

    fun setArtist(name: String) {
        viewModelScope.launch { libraryRepository.batchEditTracks(listOf(trackId), name, null, null, null) }
    }

    fun setAlbum(name: String) {
        viewModelScope.launch { libraryRepository.batchEditTracks(listOf(trackId), null, name, null, null) }
    }

    /** Blank clears the genre (see LibraryRepositoryImpl.batchEditTracks) - null here would mean
     * "leave unchanged", which this single-field editor never wants. */
    fun setGenre(genre: String) {
        viewModelScope.launch { libraryRepository.batchEditTracks(listOf(trackId), null, null, null, genre) }
    }

    /** Year lives on the album, not the track - no-op if this track has no album to attach it to. */
    fun setYear(year: Int) {
        val albumId = track.value?.albumId ?: return
        viewModelScope.launch { libraryRepository.setAlbumYear(albumId, year) }
    }

    fun setRating(rating: Int?) {
        viewModelScope.launch { libraryRepository.setTrackRating(trackId, rating) }
    }

    fun setNote(note: String?) {
        viewModelScope.launch { libraryRepository.setTrackNote(trackId, note) }
    }
}
