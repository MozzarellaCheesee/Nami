package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.JamRepository
import dev.nami.domain.JamSession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamRepository: JamRepository,
) : ViewModel() {

    val session: StateFlow<JamSession?> = jamRepository.session
    val error: StateFlow<String?> = jamRepository.error
    val connected: StateFlow<Boolean> = jamRepository.connected
    val isServerConfigured: StateFlow<Boolean> = jamRepository.isServerConfigured
    val activeHostUrl: StateFlow<String?> = jamRepository.activeHostUrl

    fun createRoom() = jamRepository.createRoom()
    fun joinRoom(code: String, hostUrl: String? = null) = jamRepository.joinRoom(code, hostUrl)
    fun leave() = jamRepository.leave()
    fun play(serverTrackId: Long, positionMs: Long = 0) = jamRepository.play(serverTrackId, positionMs)
    fun seek(positionMs: Long) = jamRepository.seek(positionMs)
    fun addToQueue(serverTrackId: Long) = jamRepository.addToQueue(serverTrackId)
    fun clearError() = jamRepository.clearError()
}
