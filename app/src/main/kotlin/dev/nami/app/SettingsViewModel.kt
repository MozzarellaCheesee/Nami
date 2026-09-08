package dev.nami.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.data.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    val autoOpenPlayer: StateFlow<Boolean> = appSettingsRepository.autoOpenPlayer
    val hideSystemBars: StateFlow<Boolean> = appSettingsRepository.hideSystemBars
    val karaokeEnabled: StateFlow<Boolean> = appSettingsRepository.karaokeEnabled
    val studyModeEnabled: StateFlow<Boolean> = appSettingsRepository.studyModeEnabled
    val lyricsFontPath: StateFlow<String?> = appSettingsRepository.lyricsFontPath
    val stands4Uid: StateFlow<String> = appSettingsRepository.stands4Uid
    val stands4Token: StateFlow<String> = appSettingsRepository.stands4Token
    val stands4RequestsToday: StateFlow<Int> = appSettingsRepository.stands4RequestsToday
    val deeplApiKey: StateFlow<String> = appSettingsRepository.deeplApiKey
    val shuffleMode: StateFlow<dev.nami.domain.ShuffleMode> = appSettingsRepository.shuffleMode
    val amoledEnabled: StateFlow<Boolean> = appSettingsRepository.amoledEnabled
    val uiFontPath: StateFlow<String?> = appSettingsRepository.uiFontPath
    val doubleTapArtworkAction: StateFlow<dev.nami.domain.GestureAction> = appSettingsRepository.doubleTapArtworkAction
    val scrobblingEnabled: StateFlow<Boolean> = appSettingsRepository.scrobblingEnabled
    val listenBrainzToken: StateFlow<String?> = appSettingsRepository.listenBrainzToken

    fun setAutoOpenPlayer(value: Boolean) {
        appSettingsRepository.setAutoOpenPlayer(value)
    }

    fun setHideSystemBars(value: Boolean) {
        appSettingsRepository.setHideSystemBars(value)
    }

    fun setKaraokeEnabled(value: Boolean) {
        appSettingsRepository.setKaraokeEnabled(value)
    }

    fun setStudyModeEnabled(value: Boolean) {
        appSettingsRepository.setStudyModeEnabled(value)
    }

    /** [uri] is a content:// pick - not a stable path, so the font file is copied into app
     * storage once and only the local copy's path is kept. */
    fun pickLyricsFont(uri: Uri) {
        viewModelScope.launch {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ttf"
            val dest = File(context.filesDir, "fonts/lyrics.$extension")
            val copied = withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            }
            if (copied) appSettingsRepository.setLyricsFontPath(dest.path)
        }
    }

    fun clearLyricsFont() {
        appSettingsRepository.setLyricsFontPath(null)
    }

    fun setStands4Uid(value: String) {
        appSettingsRepository.setStands4Uid(value)
    }

    fun setStands4Token(value: String) {
        appSettingsRepository.setStands4Token(value)
    }

    fun setDeeplApiKey(value: String) {
        appSettingsRepository.setDeeplApiKey(value)
    }

    fun setShuffleMode(mode: dev.nami.domain.ShuffleMode) {
        appSettingsRepository.setShuffleMode(mode)
    }

    fun setAmoledEnabled(value: Boolean) {
        appSettingsRepository.setAmoledEnabled(value)
    }

    /** Группа E "свой шрифт интерфейса" - тот же copy-once-to-local-storage приём что
     * pickLyricsFont, т.к. [uri] не стабилен между запусками. */
    fun pickUiFont(uri: Uri) {
        viewModelScope.launch {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ttf"
            val dest = File(context.filesDir, "fonts/ui.$extension")
            val copied = withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            }
            if (copied) appSettingsRepository.setUiFontPath(dest.path)
        }
    }

    fun clearUiFont() {
        appSettingsRepository.setUiFontPath(null)
    }

    fun setDoubleTapArtworkAction(action: dev.nami.domain.GestureAction) {
        appSettingsRepository.setDoubleTapArtworkAction(action)
    }

    fun setScrobblingEnabled(value: Boolean) {
        appSettingsRepository.setScrobblingEnabled(value)
    }

    fun setListenBrainzToken(token: String) {
        appSettingsRepository.setListenBrainzToken(token)
    }
}
