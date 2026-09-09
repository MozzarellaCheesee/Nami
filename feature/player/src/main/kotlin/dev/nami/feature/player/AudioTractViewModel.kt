package dev.nami.feature.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.player.convolution.IrWavLoader
import dev.nami.player.output.DeviceAudioProbe
import dev.nami.player.usb.UsbAudioProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
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
    val smartCrossfadeEnabled: Boolean = false,
    val crossfeedEnabled: Boolean = false,
    val convolutionEnabled: Boolean = false,
    val convolutionIrPath: String? = null,
    val deviceAudioProfile: String? = null,
    /** Тест устройства идёт секунды (строит и рушит десятки AudioTrack) - экран должен показать,
     * что он идёт, иначе кнопка выглядит сломанной. */
    val deviceProbeRunning: Boolean = false,
    /** Что сообщает о себе подключённый USB-ЦАП. Не сохраняется между запусками: зависит от того,
     * что воткнуто прямо сейчас. */
    val usbAudioInfo: String? = null,
    /** Ключ и имя того устройства вывода, что подключено прямо сейчас - под него сохраняется
     * персональный EQ-профиль. Имя null у встроенного динамика. */
    val currentDeviceKey: String = "",
    val currentDeviceName: String? = null,
    val outputDeviceProfiles: Map<String, OutputProfile> = emptyMap(),
)

/** Feeds both План.md's 4.6 "Аудиотракт" and 4.7 "Эквалайзер" screens - same underlying state,
 * split into two screens because that's how the design mock has it. */
@HiltViewModel
class AudioTractViewModel @Inject constructor(
    playerRepository: PlayerRepository,
    libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Was filterIsInstance<Playing>() - which never emits at all while nothing is playing, so
    // the whole combine() below stayed stuck on its initial value forever and every toggle looked
    // like it silently reverted (it was actually saved fine, the screen just never redrew). Falls
    // back to a null track instead of blocking, so settings work regardless of playback state.
    // Объявлено до uiState: тот их читает в combine, а Kotlin инициализирует свойства сверху вниз.
    private val deviceProbeRunning = MutableStateFlow(false)
    private val usbAudioInfo = MutableStateFlow<String?>(null)

    /** Свой экземпляр детектора: тот, что живёт в PlaybackService, экрану недоступен (это другой
     * процессный компонент, а не общий синглтон), а знать, какое устройство подключено прямо
     * сейчас, экрану нужно живьём - пользователь может воткнуть наушники, не уходя с экрана.
     * Регистрация AudioDeviceCallback дешёвая, снимается в onCleared. */
    private val outputDeviceDetector = dev.nami.player.output.OutputDeviceDetector(context)

    override fun onCleared() {
        outputDeviceDetector.release()
        super.onCleared()
    }

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
        settingsRepository.smartCrossfadeEnabled,
        settingsRepository.crossfeedEnabled,
        settingsRepository.convolutionEnabled,
        settingsRepository.convolutionIrPath,
        settingsRepository.deviceAudioProfile,
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
            smartCrossfadeEnabled = values[11] as Boolean,
            crossfeedEnabled = values[12] as Boolean,
            convolutionEnabled = values[13] as Boolean,
            convolutionIrPath = values[14] as String?,
            deviceAudioProfile = values[15] as String?,
        )
    }
        .combine(deviceProbeRunning) { state, running -> state.copy(deviceProbeRunning = running) }
        .combine(usbAudioInfo) { state, usb -> state.copy(usbAudioInfo = usb) }
        .combine(outputDeviceDetector.currentKey) { state, key -> state.copy(currentDeviceKey = key) }
        .combine(outputDeviceDetector.currentName) { state, name -> state.copy(currentDeviceName = name) }
        .combine(settingsRepository.outputDeviceProfiles) { state, profiles -> state.copy(outputDeviceProfiles = profiles) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioTractUiState())

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

    /** Сохранить текущую кривую EQ как профиль подключённого сейчас устройства (или стереть его,
     * если profile == null). Заодно включает сами профили - иначе кнопка сохраняет в пустоту. */
    fun saveCurrentEqAsDeviceProfile() {
        val state = uiState.value
        val existing = state.outputDeviceProfiles[state.currentDeviceKey]
        settingsRepository.setOutputDeviceProfile(
            state.currentDeviceKey,
            OutputProfile(state.eqBandGains, existing?.volumeLimitPercent ?: 100),
        )
        if (!state.outputProfilesEnabled) settingsRepository.setOutputProfilesEnabled(true)
    }

    fun deleteCurrentDeviceProfile() {
        settingsRepository.setOutputDeviceProfile(uiState.value.currentDeviceKey, null)
    }

    fun setSmartCrossfadeEnabled(enabled: Boolean) = settingsRepository.setSmartCrossfadeEnabled(enabled)

    fun setCrossfeedEnabled(enabled: Boolean) = settingsRepository.setCrossfeedEnabled(enabled)

    fun setConvolutionEnabled(enabled: Boolean) = settingsRepository.setConvolutionEnabled(enabled)

    /** Импульс копируется из SAF в files/ir/ - см. SettingsRepository.convolutionIrPath про то,
     * почему не хранится сам Uri. Разбор тут же, чтобы сразу отказать по кривому файлу, а не
     * молча не дать звука после включения тумблера. */
    fun importImpulseResponse(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val directory = File(context.filesDir, "ir").apply { mkdirs() }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "impulse.wav"
            val target = File(directory, name)
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("не открылся поток")
            }.isSuccess
            if (!copied || IrWavLoader.load(target) == null) {
                target.delete()
                _irImportError.value = "Не удалось прочитать импульс - нужен WAV"
                return@launch
            }
            // Прошлый импульс больше не нужен, и лежит он в приватной папке приложения, которую
            // пользователь сам не почистит.
            settingsRepository.convolutionIrPath.value
                ?.takeIf { it != target.absolutePath }
                ?.let { runCatching { File(it).delete() } }
            settingsRepository.setConvolutionIrPath(target.absolutePath)
        }
    }

    private val _irImportError = MutableStateFlow<String?>(null)
    val irImportError: StateFlow<String?> = _irImportError
    fun irImportErrorShown() { _irImportError.value = null }

    /** П.md §11 "Тест устройства". Строго не на главном потоке: перебор строит и рушит десятки
     * настоящих AudioTrack, каждый из которых ходит в аудиосервер. */
    fun runDeviceProbe() {
        if (deviceProbeRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            deviceProbeRunning.value = true
            try {
                settingsRepository.setDeviceAudioProfile(DeviceAudioProbe.probe())
                // Заодно спрашиваем сам USB-ЦАП, если он воткнут: это данные от устройства, а не
                // от микшера Android, и расходятся они регулярно.
                val usb = UsbAudioProbe(context)
                usbAudioInfo.value = usb.audioDevices().joinToString("\n\n") { device ->
                    "${device.productName ?: device.deviceName}\n${usb.describe(device)}"
                }.takeIf { it.isNotBlank() }
            } finally {
                deviceProbeRunning.value = false
            }
        }
    }
}
