package dev.nami.domain

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.StateFlow

/** Группа G "сеть" - один локальный HTTP-сервер на устройстве плюс NSD (Network Service
 * Discovery) для автопоиска, QR-код вручную как запасной путь когда автопоиск ничего не находит.
 * Никакого Wi-Fi Direct - на многих телефонах он ненадёжен, обычный сокет по локальной сети
 * работает везде одинаково. Всё в пределах LAN, наружу ничего не уходит. */
data class DiscoveredDevice(val name: String, val host: String, val port: Int)

/** Wi-Fi Direct peer до подключения - "видимое" устройство рядом, но ещё не в общей IP-сети
 * (появится в [LocalShareRepository.discoveredDevices] как обычный [DiscoveredDevice] только
 * после [LocalShareRepository.connectWifiDirect]). [status] - человекочитаемое состояние
 * (WifiP2pDevice.deviceStatus), не enum - используется только для отображения. */
data class WifiDirectPeer(val name: String, val address: String, val status: String)

/** П.md §24.25 "через интернет — опционально". Прямое P2P-соединение (WebRTC DataChannel,
 * публичный STUN Google, без своего сервера) для случая, когда оба устройства в разных сетях -
 * ни NSD, ни Wi-Fi Direct тут не достанут (обоим нужна общая радио-видимость). Обмен кодами
 * приглашение/ответ - вручную (скопировать и отправить любым способом), потому что без своего
 * сервера сигналинг больше неоткуда взять. */
enum class InternetLinkState { IDLE, CONNECTING, CONNECTED, FAILED }

data class ListenTogetherGuestState(
    val hostName: String,
    val trackId: TrackId?,
    val trackTitle: String?,
    val artistName: String?,
    /** true пока качается текущий трек - воспроизведение начнётся когда станет false. */
    val downloading: Boolean,
    /** Путь к треку в кэше сессии (не в библиотеке) - null пока не скачан. */
    val cachedPath: String?,
    val positionMs: Long,
    val durationMs: Long,
)

interface LocalShareRepository {
    val serverRunning: StateFlow<Boolean>
    /** "192.168.x.x:PORT" пока сервер работает, иначе null. */
    val serverAddress: StateFlow<String?>
    fun startServer()
    fun stopServer()

    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    fun startDiscovery()
    fun stopDiscovery()

    /** Разбирает "host:port" (в т.ч. из отсканированного QR) в устройство для ручного добавления
     * когда автопоиск не нашёл. */
    fun parseManualAddress(text: String): DiscoveredDevice?

    /** Что прямо сейчас отдаётся другим устройствам через /drop - null значит ничего. */
    val dropTrack: StateFlow<Track?>
    fun setDropTrack(track: Track?)

    /** Скачивает то, что [device] сейчас раздаёт (см. [dropTrack] на его стороне), и сразу
     * добавляет в библиотеку тем же импортом что обычный выбор файла - Wi-Fi Drop это осознанная
     * передача, не временный кэш как "слушать вместе". */
    suspend fun pullDrop(device: DiscoveredDevice): Boolean

    /** Тянет манифест с [device] и переносит лайки/рейтинги на СВОИ треки, совпадающие по
     * названию/исполнителю/длительности (та же нечёткая эвристика что дедуп при импорте) -
     * не создаёт и не удаляет треки, только обновляет метаданные уже существующих. */
    suspend fun syncWith(device: DiscoveredDevice): Int

    /** Включает раздачу /nowplaying - без этого другие устройства не видят, что сейчас играет
     * (приватность по умолчанию, не автоматически при старте сервера). */
    val listenTogetherHostEnabled: StateFlow<Boolean>
    fun setListenTogetherHost(enabled: Boolean)

    val listenTogetherGuestState: StateFlow<ListenTogetherGuestState?>
    fun joinListenTogether(device: DiscoveredDevice)
    fun leaveListenTogether()

    /** Добавляет ТЕКУЩИЙ скачанный в сессии трек в постоянную библиотеку (обычным импортом) -
     * до этого он живёт только в кэше сессии и удаляется при выходе из "слушать вместе". */
    suspend fun addCurrentListenTogetherTrackToLibrary(): Boolean

    /** Wi-Fi Direct - работает БЕЗ роутера и БЕЗ интернета (устройства напрямую договариваются
     * о своей собственной IP-сети), в отличие от NSD-автопоиска [discoveredDevices], которому
     * нужна общая Wi-Fi сеть. Тот же сценарий "слушать вместе"/Wi-Fi Drop/синхронизация -
     * подключённый peer появляется в [discoveredDevices] как обычное устройство, никакого
     * отдельного UI-пути для него не требуется. */
    val wifiDirectPeers: StateFlow<List<WifiDirectPeer>>
    val wifiDirectConnecting: StateFlow<Boolean>
    fun startWifiDirectDiscovery()
    fun stopWifiDirectDiscovery()
    fun connectWifiDirect(peer: WifiDirectPeer)

    /** Интернет-мост через WebRTC (см. [InternetLinkState] doc) - хост и гость обмениваются
     * кодами вручную (текстом, любым мессенджером). После установки канала хост пушит nowplaying
     * (тот же переключатель [listenTogetherHostEnabled]) и отдаёт байты трека гостю, гость
     * попадает в тот же [listenTogetherGuestState], что и LAN-версия "слушать вместе". */
    val internetLinkState: StateFlow<InternetLinkState>
    /** Хост: создаёт offer и ждёт сбора ICE-кандидатов, возвращает код приглашения. */
    suspend fun createInternetInvite(): String
    /** Гость: принимает код приглашения, возвращает код ответа для хоста. */
    suspend fun acceptInternetInvite(inviteCode: String): String
    /** Хост: завершает handshake кодом ответа гостя. */
    suspend fun completeInternetLink(answerCode: String)
    fun closeInternetLink()
}
