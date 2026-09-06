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

    fun setAutoOpenPlayer(value: Boolean) {
        appSettingsRepository.setAutoOpenPlayer(value)
    }
}
