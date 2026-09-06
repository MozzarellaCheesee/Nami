package dev.nami.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "nami_settings"
private const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"

@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Default OFF: tapping a track starts playback without jumping to Now Playing, per the
    // explicit request this setting exists for -- opening the full player is opt-in.
    private val _autoOpenPlayer = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_PLAYER, false))
    val autoOpenPlayer: StateFlow<Boolean> = _autoOpenPlayer

    fun setAutoOpenPlayer(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_OPEN_PLAYER, value) }
        _autoOpenPlayer.value = value
    }
}
