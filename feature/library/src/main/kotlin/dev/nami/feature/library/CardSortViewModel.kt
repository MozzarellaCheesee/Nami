package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SwipeDirection { LEFT, RIGHT, UP }

/** Группа D "карточный разбор библиотеки (свайпы)" - Tinder-стиль по всей библиотеке: вправо
 * добавляет в "Любимые треки" (см. PlaylistRepository.likeTrack), влево отправляет в корзину
 * (мягкое удаление, восстановимо 30 дней как везде), вверх просто пропускает. Без отмены
 * последнего действия - следующий шаг той же задачи, если понадобится. */
@HiltViewModel
class CardSortViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    data class UiState(
        val queue: List<Track> = emptyList(),
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            _uiState.value = UiState(queue = libraryRepository.allTracksOrdered().shuffled(), loading = false)
        }
    }

    fun swipe(direction: SwipeDirection) {
        val current = _uiState.value.queue.firstOrNull() ?: return
        val rest = _uiState.value.queue.drop(1)
        _uiState.value = _uiState.value.copy(queue = rest)
        viewModelScope.launch {
            when (direction) {
                SwipeDirection.RIGHT -> playlistRepository.likeTrack(current.id)
                SwipeDirection.LEFT -> libraryRepository.deleteTrack(current.id)
                SwipeDirection.UP -> Unit
            }
        }
    }
}
