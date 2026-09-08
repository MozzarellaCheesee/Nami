package dev.nami.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Группа D "слепое прослушивание" - MiniPlayer иначе палит название/обложку/исполнителя пока
 * играет слепой раунд, что убивает весь смысл экрана. BlindListenViewModel включает флаг на
 * время своей жизни, MiniPlayer/NowPlayingViewModel читают его чтобы прятать метаданные. */
@Singleton
class BlindListenState @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun setActive(value: Boolean) {
        _active.value = value
    }
}
