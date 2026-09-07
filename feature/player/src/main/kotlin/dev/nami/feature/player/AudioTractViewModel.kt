package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.LibraryRepository
import dev.nami.domain.OutputDeviceType
import dev.nami.domain.OutputProfile
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.SettingsRepository
import dev.nami.player.eq.ParametricEqAudioProcessor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AudioTractUiState(
    val track: Track? = null,
    val eqEnabled: Boolean = false,
    val eqBandGains: List<Float> = List(ParametricEqAudioProcessor.BAND_FREQS_HZ.size) { 0f },
    val bitPerfectUsbEnabled: Boolean = false,
    val replayGainEnabled: Boolean = false,
    val ditherEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val playbackGainDb: Float = 0f,
    val hiFiEnabled: Boolean = false,
    val outputProfilesEnabled: Boolean = false,
    val outputProfiles: Map<OutputDeviceType, OutputProfile> = emptyMap(),
)

/** Feeds both План.md's 4.6 "Аудиотракт" and 4.7 "Эквалайзер" screens -- same underlying state,
 * split into two screens because that's how the design mock has it. */
@HiltViewModel
class AudioTractViewModel @Inject constructor(
    playerRepository: PlayerRepository,
    libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // Was filterIsInstance<Playing>() -- which never emits at all while nothing is playing, so
    // the whole combine() below stayed stuck on its initial value forever and every toggle looked
    // like it silently reverted (it was actually saved fine, the screen just never redrew). Falls
    // back to a null track instead of blocking, so settings work regardless of playback state.
    private val currentTrack = playerRepository.state
        .flatMapLatest { state ->
            if (state is PlaybackState.Playing) libraryRepository.track(state.trackId) else flowOf(null)
        }

    val uiState: StateFlow<AudioTractUiState> = combine(
        currentTrack,
        settingsRepository.eqEnabled,
        settingsRepository.eqBandGains,
        settingsRepository.bitPerfectUsbEnabled,
        settingsRepository.replayGainEnabled,
        settingsRepository.ditherEnabled,
        settingsRepository.crossfadeEnabled,
        settingsRepository.playbackGainDb,
        settingsRepository.hiFiEnabled,
        settingsRepository.outputProfilesEnabled,
        settingsRepository.outputProfiles,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AudioTractUiState(
            track = values[0] as Track?,
            eqEnabled = values[1] as Boolean,
            eqBandGains = values[2] as List<Float>,
            bitPerfectUsbEnabled = values[3] as Boolean,
            replayGainEnabled = values[4] as Boolean,
            ditherEnabled = values[5] as Boolean,
            crossfadeEnabled = values[6] as Boolean,
            playbackGainDb = values[7] as Float,
            hiFiEnabled = values[8] as Boolean,
            outputProfilesEnabled = values[9] as Boolean,
            outputProfiles = values[10] as Map<OutputDeviceType, OutputProfile>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioTractUiState())

    fun setEqEnabled(enabled: Boolean) = settingsRepository.setEqEnabled(enabled)

    fun setEqBandGains(gainsDb: List<Float>) = settingsRepository.setEqBandGains(gainsDb)

    fun setReplayGainEnabled(enabled: Boolean) = settingsRepository.setReplayGainEnabled(enabled)

    fun setDitherEnabled(enabled: Boolean) = settingsRepository.setDitherEnabled(enabled)

    fun setCrossfadeEnabled(enabled: Boolean) = settingsRepository.setCrossfadeEnabled(enabled)

    fun setPlaybackGainDb(gainDb: Float) = settingsRepository.setPlaybackGainDb(gainDb)

    fun setBitPerfectUsbEnabled(enabled: Boolean) = settingsRepository.setBitPerfectUsbEnabled(enabled)

    fun setHiFiEnabled(enabled: Boolean) = settingsRepository.setHiFiEnabled(enabled)

    fun setOutputProfilesEnabled(enabled: Boolean) = settingsRepository.setOutputProfilesEnabled(enabled)

    fun setOutputProfile(type: OutputDeviceType, profile: OutputProfile) = settingsRepository.setOutputProfile(type, profile)
}
