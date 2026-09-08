package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Группа D "слепое прослушивание" - случайный трек играет с закрытыми обложкой/названием/
 * исполнителем, пока не нажмёшь "Раскрыть". Отдельная игра-угадайка, не то же самое что A/B
 * сравнение версий (там сравниваются два конкретных варианта одного трека). */
@HiltViewModel
class BlindListenViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    data class UiState(
        val current: Track? = null,
        val revealed: Boolean = false,
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
    val playbackState: StateFlow<PlaybackState> = playerRepository.state

    init {
        next()
    }

    fun next() {
        _uiState.value = UiState(loading = true)
        viewModelScope.launch {
            val library = libraryRepository.allTracksOrdered()
            val track = library.randomOrNull()
            if (track == null) {
                _uiState.value = UiState(loading = false)
                return@launch
            }
            playerRepository.play(
                listOf(
                    PlayableTrack(
                        id = track.id,
                        title = track.title,
                        artistName = track.artistName,
                        path = track.path,
                        artworkPath = track.albumArtworkPath,
                        format = track.format,
                        cueStartMs = track.cueStartMs,
                        cueEndMs = track.cueEndMs,
                    ),
                ),
                startIndex = 0,
            )
            _uiState.value = UiState(current = track, revealed = false, loading = false)
        }
    }

    fun reveal() {
        _uiState.value = _uiState.value.copy(revealed = true)
    }

    fun togglePlayback() {
        viewModelScope.launch { playerRepository.toggle() }
    }
}
