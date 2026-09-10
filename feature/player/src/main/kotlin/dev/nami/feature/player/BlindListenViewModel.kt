package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Группа D "слепое прослушивание" - случайный трек играет с закрытыми обложкой/названием/
 * исполнителем, пока не нажмёшь "Раскрыть". Отдельная игра-угадайка, не то же самое что A/B
 * сравнение версий (там сравниваются два конкретных варианта одного трека). */
@HiltViewModel
class BlindListenViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
    private val playlistRepository: PlaylistRepository,
    private val blindListenState: BlindListenState,
) : ViewModel() {
    data class UiState(
        val current: Track? = null,
        val revealed: Boolean = false,
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
    val playbackState: StateFlow<PlaybackState> = playerRepository.state

    val isCurrentTrackLiked: StateFlow<Boolean> = _uiState
        .map { it.current?.id }
        .distinctUntilChanged()
        .filterNotNull()
        .flatMapLatest { playlistRepository.isTrackLiked(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        blindListenState.setActive(true)
        next()
    }

    fun toggleLike() {
        val id = _uiState.value.current?.id ?: return
        viewModelScope.launch { playlistRepository.toggleLike(id) }
    }

    override fun onCleared() {
        blindListenState.setActive(false)
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
                        durationMs = track.durationMs,
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
