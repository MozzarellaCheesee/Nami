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
data class WifiDirectPeer(
    val name: String,
    val address: String,
    val status: String,
    /** true - устройство прямо сейчас в одной Wi-Fi Direct группе с нами (сопряжено). Берётся из
     * состава группы (WifiP2pGroup), а не из результатов поиска: поиск после переоткрытия экрана
     * начинается с нуля и уже сопряжённое устройство в нём может не появиться вовсе, хотя группа
     * жива - экран тогда показывал "никого не видно рядом" при активном соединении. Состав группы
     * симметричен: владелец видит клиентов, клиент видит владельца. */
    val connected: Boolean = false,
)

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
    /** Почему гостевая сессия не идёт: хост недоступен, не включил "показывать что играю", или
     * трек не скачался. null - всё в порядке. Без этого гость молча смотрел в пустой экран (или
     * в вечное "Скачивается...") и не мог отличить "ещё грузится" от "уже никогда". */
    val listenTogetherError: StateFlow<String?>
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
    /** true пока группа Wi-Fi Direct реально сформирована (не только "жмём подключиться") -
     * группа держится на уровне ОС, сама по себе не рвётся, пока не позвать [disconnectWifiDirect]. */
    val wifiDirectConnected: StateFlow<Boolean>
    fun startWifiDirectDiscovery()
    fun stopWifiDirectDiscovery()
    fun connectWifiDirect(peer: WifiDirectPeer)
    /** Рвёт текущую группу Wi-Fi Direct (WifiP2pManager.removeGroup) - без этого группа остаётся
     * подключённой навсегда на обеих сторонах, экран так и продолжает показывать "подключено". */
    fun disconnectWifiDirect()
}
