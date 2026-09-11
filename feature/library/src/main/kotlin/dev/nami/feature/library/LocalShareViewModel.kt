package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.DiscoveredDevice
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListenTogetherGuestState
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.PlayerRepository
import dev.nami.domain.WifiDirectPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Группа G "сеть" - см. LocalShareRepository. Один экран на три сценария (Wi-Fi Drop,
 * синхронизация, слушать вместе), они делят один и тот же список найденных устройств. */
@HiltViewModel
class LocalShareViewModel @Inject constructor(
    private val repository: LocalShareRepository,
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    val serverRunning: StateFlow<Boolean> = repository.serverRunning
    val serverAddress: StateFlow<String?> = repository.serverAddress
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = repository.discoveredDevices
    val dropTrack: StateFlow<Track?> = repository.dropTrack
    val listenTogetherHostEnabled: StateFlow<Boolean> = repository.listenTogetherHostEnabled
    val listenTogetherGuestCount: StateFlow<Int> = repository.listenTogetherGuestCount
    val listenTogetherGuestState: StateFlow<ListenTogetherGuestState?> = repository.listenTogetherGuestState
    val listenTogetherError: StateFlow<String?> = repository.listenTogetherError
    val wifiDirectPeers: StateFlow<List<WifiDirectPeer>> = repository.wifiDirectPeers
    val wifiDirectConnecting: StateFlow<Boolean> = repository.wifiDirectConnecting
    val wifiDirectConnected: StateFlow<Boolean> = repository.wifiDirectConnected
    private val _lastSyncResult = MutableStateFlow<Int?>(null)
    val lastSyncResult: StateFlow<Int?> = _lastSyncResult
    private val _lastPullResult = MutableStateFlow<Boolean?>(null)
    val lastPullResult: StateFlow<Boolean?> = _lastPullResult
    private val _dropError = MutableStateFlow<String?>(null)
    val dropError: StateFlow<String?> = _dropError

    init {
        repository.startServer()
        repository.startDiscovery()
    }

    override fun onCleared() {
        repository.stopDiscovery()
        repository.stopWifiDirectDiscovery()
        // Сервер и "слушать вместе" намеренно НЕ останавливаются здесь - экран может закрыться,
        // пока другое устройство ещё качает раздачу или гость всё ещё в сессии.
    }

    fun startWifiDirectDiscovery() = repository.startWifiDirectDiscovery()
    fun connectWifiDirect(peer: WifiDirectPeer) = repository.connectWifiDirect(peer)
    fun disconnectWifiDirect() = repository.disconnectWifiDirect()

    /** NSD-автопоиск запускается в init() ещё до того, как экран успевает спросить
     * NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION - на Android 13+ без этого разрешения система
     * тихо не отдаёт вообще ни одного найденного устройства (не ошибка, просто пустой список),
     * так что после получения разрешения поиск нужно перезапустить, иначе он так и останется
     * "включённым", но слепым. */
    fun restartDiscovery() {
        repository.stopDiscovery()
        repository.startDiscovery()
    }

    fun setDropCurrentTrack() {
        _dropError.value = null
        val nowPlaying = playerRepository.queue.value.nowPlaying
        if (nowPlaying == null) {
            // Раньше молча ничего не делал - экран так и оставался "Ничего не раздаётся" без
            // объяснения, выглядело как сломанная раздача, а на деле просто ничего не играет.
            _dropError.value = "Сначала запусти воспроизведение трека"
            return
        }
        viewModelScope.launch {
            val track = libraryRepository.track(nowPlaying.id).first()
            if (track == null) {
                _dropError.value = "Не нашёл этот трек в библиотеке"
                return@launch
            }
            repository.setDropTrack(track)
        }
    }

    fun clearDropTrack() {
        repository.setDropTrack(null)
    }

    fun pullDrop(device: DiscoveredDevice) {
        viewModelScope.launch {
            _lastPullResult.value = repository.pullDrop(device)
        }
    }

    fun syncWith(device: DiscoveredDevice) {
        viewModelScope.launch {
            _lastSyncResult.value = repository.syncWith(device)
        }
    }

    fun joinListenTogether(device: DiscoveredDevice) = repository.joinListenTogether(device)
    fun leaveListenTogether() = repository.leaveListenTogether()

    fun addCurrentListenTogetherTrackToLibrary(onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = repository.addCurrentListenTogetherTrackToLibrary()
            onDone?.invoke(ok)
        }
    }

    fun setListenTogetherHost(enabled: Boolean) = repository.setListenTogetherHost(enabled)

    fun addManualDevice(text: String): DiscoveredDevice? = repository.parseManualAddress(text)
}
