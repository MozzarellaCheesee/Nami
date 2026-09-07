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
}
