package dev.nami.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.data.AppSettingsRepository
import dev.nami.domain.PlayerRepository
import dev.nami.domain.Session
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** План.md §22.11 "Сессии" - captures the current EQ/crossfade state under a name, and later
 * re-applies it plus (optionally) starts a sleep timer, all in one tap. Lives in :app (not
 * :feature:player) because it's the only place that already has both SettingsRepository (EQ,
 * crossfade) and PlayerRepository (sleep timer) in the same graph without adding a new
 * cross-module dependency just for this screen. */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val sessions: StateFlow<List<Session>> = appSettingsRepository.sessions

    fun saveCurrentAsSession(name: String, sleepTimerMinutes: Int?) {
        if (name.isBlank()) return
        appSettingsRepository.saveSession(
            Session(
                name = name,
                eqGainsDb = appSettingsRepository.eqBandGains.value,
                crossfadeEnabled = appSettingsRepository.crossfadeEnabled.value,
                sleepTimerMinutes = sleepTimerMinutes,
                shuffleEnabled = playerRepository.shuffleEnabled.value,
                repeatMode = playerRepository.repeatMode.value,
            ),
        )
    }

    fun deleteSession(name: String) {
        appSettingsRepository.deleteSession(name)
    }

    fun applySession(session: Session) {
        appSettingsRepository.setEqBandGains(session.eqGainsDb)
        appSettingsRepository.setEqEnabled(true)
        appSettingsRepository.setCrossfadeEnabled(session.crossfadeEnabled)
        viewModelScope.launch {
            playerRepository.setShuffleEnabled(session.shuffleEnabled)
            playerRepository.setRepeatMode(session.repeatMode)
        }
        session.sleepTimerMinutes?.let { minutes ->
            viewModelScope.launch { playerRepository.startSleepTimer(minutes * 60_000L) }
        }
    }
}
