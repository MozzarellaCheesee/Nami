package dev.nami.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.data.AppSettingsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    val autoOpenPlayer: StateFlow<Boolean> = appSettingsRepository.autoOpenPlayer
    val hideSystemBars: StateFlow<Boolean> = appSettingsRepository.hideSystemBars
    val karaokeEnabled: StateFlow<Boolean> = appSettingsRepository.karaokeEnabled

    fun setAutoOpenPlayer(value: Boolean) {
        appSettingsRepository.setAutoOpenPlayer(value)
    }

    fun setHideSystemBars(value: Boolean) {
        appSettingsRepository.setHideSystemBars(value)
    }

    fun setKaraokeEnabled(value: Boolean) {
        appSettingsRepository.setKaraokeEnabled(value)
    }
}
