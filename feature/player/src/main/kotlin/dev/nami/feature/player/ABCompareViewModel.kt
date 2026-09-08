package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Хвост группы C "слепое A/B сравнение версий" -- ждёт TrackVersionGrouper (уже есть, группирует
 * remix/live/acoustic по названию). Играет оба варианта под нейтральными ярлыками "Вариант 1/2",
 * переключение сохраняет позицию, названия раскрываются отдельной кнопкой -- решение принимается
 * на слух, не по названию файла. */
@HiltViewModel
class ABCompareViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    data class UiState(
        val trackA: PlayableTrack? = null,
        val trackB: PlayableTrack? = null,
        val currentLabel: Int = 1,
        val revealed: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
    val playbackState: StateFlow<PlaybackState> = playerRepository.state

    fun start(a: PlayableTrack, b: PlayableTrack) {
        _uiState.value = UiState(trackA = a, trackB = b, currentLabel = 1, revealed = false)
        viewModelScope.launch { playerRepository.play(listOf(a, b), startIndex = 0) }
    }

    fun switchTo(label: Int) {
        val state = _uiState.value
        if (state.currentLabel == label || state.trackA == null || state.trackB == null) return
        val positionMs = (playbackState.value as? PlaybackState.Playing)?.positionMs ?: 0L
        viewModelScope.launch {
            playerRepository.play(listOf(state.trackA, state.trackB), startIndex = label - 1, startMs = positionMs)
        }
        _uiState.value = state.copy(currentLabel = label)
    }

    fun reveal() {
        _uiState.value = _uiState.value.copy(revealed = true)
    }
}
