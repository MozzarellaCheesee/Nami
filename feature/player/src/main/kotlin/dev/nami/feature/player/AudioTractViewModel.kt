package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AudioTractUiState(
    val track: Track? = null,
    val eqEnabled: Boolean = false,
    val eqBassDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqTrebleDb: Float = 0f,
    val bitPerfectUsbEnabled: Boolean = false,
    val replayGainEnabled: Boolean = false,
    val ditherEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val playbackGainDb: Float = 0f,
)

/** Feeds both План.md's 4.6 "Аудиотракт" and 4.7 "Эквалайзер" screens -- same underlying state,
 * split into two screens because that's how the design mock has it. */
@HiltViewModel
class AudioTractViewModel @Inject constructor(
    playerRepository: PlayerRepository,
    libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val currentTrack = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .flatMapLatest { playing -> libraryRepository.track(playing.trackId) }

    val uiState: StateFlow<AudioTractUiState> = combine(
        currentTrack,
        settingsRepository.eqEnabled,
        settingsRepository.eqBassDb,
        settingsRepository.eqMidDb,
        settingsRepository.eqTrebleDb,
        settingsRepository.bitPerfectUsbEnabled,
        settingsRepository.replayGainEnabled,
        settingsRepository.ditherEnabled,
        settingsRepository.crossfadeEnabled,
        settingsRepository.playbackGainDb,
    ) { values ->
        AudioTractUiState(
            track = values[0] as Track?,
            eqEnabled = values[1] as Boolean,
            eqBassDb = values[2] as Float,
            eqMidDb = values[3] as Float,
            eqTrebleDb = values[4] as Float,
            bitPerfectUsbEnabled = values[5] as Boolean,
            replayGainEnabled = values[6] as Boolean,
            ditherEnabled = values[7] as Boolean,
            crossfadeEnabled = values[8] as Boolean,
            playbackGainDb = values[9] as Float,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioTractUiState())

    fun setEqEnabled(enabled: Boolean) = settingsRepository.setEqEnabled(enabled)

    fun setEqGains(bassDb: Float, midDb: Float, trebleDb: Float) =
        settingsRepository.setEqGains(bassDb, midDb, trebleDb)

    fun setReplayGainEnabled(enabled: Boolean) = settingsRepository.setReplayGainEnabled(enabled)

    fun setDitherEnabled(enabled: Boolean) = settingsRepository.setDitherEnabled(enabled)

    fun setCrossfadeEnabled(enabled: Boolean) = settingsRepository.setCrossfadeEnabled(enabled)

    fun setPlaybackGainDb(gainDb: Float) = settingsRepository.setPlaybackGainDb(gainDb)

    fun setBitPerfectUsbEnabled(enabled: Boolean) = settingsRepository.setBitPerfectUsbEnabled(enabled)
}
