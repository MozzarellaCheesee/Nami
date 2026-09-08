package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryHealthReport
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryHealthViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
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

    /** Сколько треков осталось без отпечатка звука. -1 = ещё не спрашивали. */
    private val _fingerprintsLeft = MutableStateFlow(-1)
    val fingerprintsLeft: StateFlow<Int> = _fingerprintsLeft.asStateFlow()

    private val _isScanningFingerprints = MutableStateFlow(false)
    val isScanningFingerprints: StateFlow<Boolean> = _isScanningFingerprints.asStateFlow()

    /** П.md §23.19. Порциями по кнопке: каждый трек в порции полностью декодируется, так что
     * запускать это само по себе на всей библиотеке - разряженный телефон без спроса.
     * Пользователь жмёт ещё раз, пока счётчик не дойдёт до нуля. */
    fun scanFingerprints() {
        if (_isScanningFingerprints.value) return
        viewModelScope.launch {
            _isScanningFingerprints.value = true
            _fingerprintsLeft.value = libraryRepository.scanFingerprints(FINGERPRINT_BATCH)
            _isScanningFingerprints.value = false
            refresh()
        }
    }

    /** Missing-file rows are just soft-deleted like any other delete - TrashFileStore's own
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

    /** Same LRCLIB-then-STANDS4 lookup LyricsScreen already uses for the manual "search" action --
     * no-op (silently) if neither source has anything, same as everywhere else that calls it. */
    fun fetchMissingLyrics(id: TrackId) {
        viewModelScope.launch {
            val track = libraryRepository.track(id).first() ?: return@launch
            val lyrics = lyricsRepository.fetchFromLrcLib(track.title, track.artistName, track.durationMs) ?: return@launch
            lyricsRepository.saveLyrics(track.path, lyrics)
            refresh()
        }
    }

    fun setAlbumYear(id: AlbumId, year: Int) {
        viewModelScope.launch {
            libraryRepository.setAlbumYear(id, year)
            refresh()
        }
    }

    /** Renames every artist row in the group to [canonicalName] (the group's first, already-
     * displayed name) so they collapse back into one Artist. */
    fun mergeArtistNames(group: List<ArtistId>, canonicalName: String) {
        viewModelScope.launch {
            group.forEach { libraryRepository.renameArtist(it, canonicalName) }
            refresh()
        }
    }

    private companion object {
        // Полсотни файлов - примерно минута сканирования, после чего экран снова отвечает.
        const val FINGERPRINT_BATCH = 50
    }
}
