package dev.nami.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "nami_settings"
private const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"
private const val KEY_HIDE_SYSTEM_BARS = "hide_system_bars"
private const val KEY_KARAOKE_ENABLED = "karaoke_enabled"
private const val KEY_STUDY_MODE_ENABLED = "study_mode_enabled"
private const val KEY_LYRICS_FONT_PATH = "lyrics_font_path"
private const val KEY_EQ_ENABLED = "eq_enabled"
private const val KEY_EQ_BAND_GAINS = "eq_band_gains" // CSV, 9 floats, BAND_FREQS_HZ order
private const val EQ_BAND_COUNT = 9
private const val KEY_BIT_PERFECT_USB_ENABLED = "bit_perfect_usb_enabled"
private const val KEY_REPLAY_GAIN_ENABLED = "replay_gain_enabled"
private const val KEY_DITHER_ENABLED = "dither_enabled"
private const val KEY_CROSSFADE_ENABLED = "crossfade_enabled"
private const val KEY_PLAYBACK_GAIN_DB = "playback_gain_db"

@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsRepository {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Default OFF: tapping a track starts playback without jumping to Now Playing, per the
    // explicit request this setting exists for -- opening the full player is opt-in.
    private val _autoOpenPlayer = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_PLAYER, false))
    override val autoOpenPlayer: StateFlow<Boolean> = _autoOpenPlayer

    override fun setAutoOpenPlayer(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_OPEN_PLAYER, value) }
        _autoOpenPlayer.value = value
    }

    // Default ON: status/nav bar hidden (immersive), matching the reference mocks' edge-to-edge
    // look -- an explicit opt-out for anyone who wants the system bars back.
    private val _hideSystemBars = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SYSTEM_BARS, true))
    override val hideSystemBars: StateFlow<Boolean> = _hideSystemBars

    override fun setHideSystemBars(value: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE_SYSTEM_BARS, value) }
        _hideSystemBars.value = value
    }

    // Default OFF: the word-level karaoke sweep is a best-effort estimate (or a Whisper pass the
    // user has to run themselves) rather than always-correct timing, so it doesn't turn on
    // uninvited -- opt-in from Settings.
    private val _karaokeEnabled = MutableStateFlow(prefs.getBoolean(KEY_KARAOKE_ENABLED, false))
    override val karaokeEnabled: StateFlow<Boolean> = _karaokeEnabled

    override fun setKaraokeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_KARAOKE_ENABLED, value) }
        _karaokeEnabled.value = value
    }

    private val _studyModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_STUDY_MODE_ENABLED, false))
    override val studyModeEnabled: StateFlow<Boolean> = _studyModeEnabled

    override fun setStudyModeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_STUDY_MODE_ENABLED, value) }
        _studyModeEnabled.value = value
    }

    private val _lyricsFontPath = MutableStateFlow(prefs.getString(KEY_LYRICS_FONT_PATH, null))
    override val lyricsFontPath: StateFlow<String?> = _lyricsFontPath

    override fun setLyricsFontPath(path: String?) {
        prefs.edit { putString(KEY_LYRICS_FONT_PATH, path) }
        _lyricsFontPath.value = path
    }

    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(KEY_EQ_ENABLED, false))
    override val eqEnabled: StateFlow<Boolean> = _eqEnabled

    override fun setEqEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_EQ_ENABLED, value) }
        _eqEnabled.value = value
    }

    private val _eqBandGains = MutableStateFlow(readEqBandGains())
    override val eqBandGains: StateFlow<List<Float>> = _eqBandGains

    override fun setEqBandGains(gainsDb: List<Float>) {
        require(gainsDb.size == EQ_BAND_COUNT) { "expected $EQ_BAND_COUNT gains, got ${gainsDb.size}" }
        prefs.edit { putString(KEY_EQ_BAND_GAINS, gainsDb.joinToString(",")) }
        _eqBandGains.value = gainsDb
    }

    private fun readEqBandGains(): List<Float> {
        val csv = prefs.getString(KEY_EQ_BAND_GAINS, null) ?: return List(EQ_BAND_COUNT) { 0f }
        val parsed = csv.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parsed.size == EQ_BAND_COUNT) parsed else List(EQ_BAND_COUNT) { 0f }
    }

    private val _bitPerfectUsbEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIT_PERFECT_USB_ENABLED, false))
    override val bitPerfectUsbEnabled: StateFlow<Boolean> = _bitPerfectUsbEnabled

    override fun setBitPerfectUsbEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_BIT_PERFECT_USB_ENABLED, value) }
        _bitPerfectUsbEnabled.value = value
    }

    private val _replayGainEnabled = MutableStateFlow(prefs.getBoolean(KEY_REPLAY_GAIN_ENABLED, false))
    override val replayGainEnabled: StateFlow<Boolean> = _replayGainEnabled

    override fun setReplayGainEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_REPLAY_GAIN_ENABLED, value) }
        _replayGainEnabled.value = value
    }

    private val _ditherEnabled = MutableStateFlow(prefs.getBoolean(KEY_DITHER_ENABLED, false))
    override val ditherEnabled: StateFlow<Boolean> = _ditherEnabled

    override fun setDitherEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_DITHER_ENABLED, value) }
        _ditherEnabled.value = value
    }

    private val _crossfadeEnabled = MutableStateFlow(prefs.getBoolean(KEY_CROSSFADE_ENABLED, false))
    override val crossfadeEnabled: StateFlow<Boolean> = _crossfadeEnabled

    override fun setCrossfadeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_CROSSFADE_ENABLED, value) }
        _crossfadeEnabled.value = value
    }

    private val _playbackGainDb = MutableStateFlow(prefs.getFloat(KEY_PLAYBACK_GAIN_DB, 0f))
    override val playbackGainDb: StateFlow<Float> = _playbackGainDb

    override fun setPlaybackGainDb(value: Float) {
        prefs.edit { putFloat(KEY_PLAYBACK_GAIN_DB, value) }
        _playbackGainDb.value = value
    }
}
