package dev.nami.player.remote

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.yinnho.upnpcast.DLNACast
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.Track
import dev.nami.domain.SettingsRepository
import dev.nami.player.LocalHttpServer
import dev.nami.player.localIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteCast"

/** Тот же порт, что у Cast (CastController): раздача на телевизор одна, приёмник в любой момент
 * один, а от Wi-Fi Drop (47821) она отделена намеренно - те две могут идти одновременно. */
private const val REMOTE_HTTP_PORT = 47822

/**
 * Единая точка "транслировать не на Chromecast": DLNA/UPnP, AirPlay (Beta) и Яндекс Станция
 * (Beta). Google Cast сюда не входит - у него свой готовый CastPlayer (см. CastController).
 *
 * Singleton, потому что говорить с ним нужно двоим: [dev.nami.player.PlaybackService] отдаёт сюда
 * доступ к локальному плееру и подмену плеера у MediaSession ([attach]), а экран выбора устройства
 * во :feature:player читает [devices]/[connected] и зовёт [connect]/[disconnect].
 */
@Singleton
@UnstableApi
class RemoteCastController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _connected = MutableStateFlow<RemoteDevice?>(null)
    val connected: StateFlow<RemoteDevice?> = _connected.asStateFlow()

    /** Последняя человекочитаемая ошибка - экран показывает её и обнуляет. Молчаливый сбой
     * трансляции пользователь не отличит от "просто не играет". */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var localPlayer: (() -> ExoPlayer)? = null
    private var trackById: ((String) -> Track?)? = null
    private var onActivePlayerChanged: ((Player) -> Unit)? = null

    private var server: LocalHttpServer? = null
    private var remotePlayer: RemotePlayer? = null
    private var searchJob: Job? = null
    private val upnpDevices = mutableMapOf<String, DLNACast.Device>()

    /** Искатели Beta-экосистем - см. init. */
    private val extraDiscoveries = mutableListOf<RemoteDiscovery>()

    init {
        // Beta-тумблер выключен - искатель просто не существует, то есть сеть на AirPlay/Станцию
        // не сканируется вообще, а не "результаты спрятаны в UI".
        settings.airPlayEnabled
            .onEach { on -> if (on) addDiscovery(AirPlayDiscovery(context)) else removeDiscovery(RemoteKind.AIRPLAY) }
            .launchIn(scope)
        settings.yandexStationEnabled
            .onEach { on ->
                if (on) addDiscovery(YandexStationDiscovery(context, settings)) else removeDiscovery(RemoteKind.YANDEX)
            }
            .launchIn(scope)
    }

    /** true, пока играем на приёмнике - владелец не должен сам переставлять плеер сессии. */
    val isRemote: Boolean get() = remotePlayer != null

    fun attach(
        localPlayer: () -> ExoPlayer,
        trackByIdBlocking: (String) -> Track?,
        onActivePlayerChanged: (Player) -> Unit,
    ) {
        this.localPlayer = localPlayer
        this.trackById = trackByIdBlocking
        this.onActivePlayerChanged = onActivePlayerChanged
    }

    private fun addDiscovery(discovery: RemoteDiscovery) {
        if (extraDiscoveries.none { it.kind == discovery.kind }) extraDiscoveries += discovery
    }

    private fun removeDiscovery(kind: RemoteKind) {
        extraDiscoveries.removeAll { it.kind == kind }
        _devices.value = _devices.value.filterNot { it.kind == kind }
    }

    fun consumeError() { _error.value = null }

    /** Один прогон поиска: SSDP для DLNA плюс всё, что добавлено через [addDiscovery]. */
    fun search() {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            _searching.value = true
            try {
                val found = mutableListOf<RemoteDevice>()
                runCatching {
                    withContext(Dispatchers.IO) {
                        DLNACast.init(context.applicationContext)
                        DLNACast.search(timeout = 5000)
                    }
                }.onSuccess { list ->
                    upnpDevices.clear()
                    list.forEach { upnpDevices[it.id] = it }
                    found += list.map { RemoteDevice(it.id, it.name, RemoteKind.DLNA, host = it.address) }
                }.onFailure { Log.w(TAG, "SSDP-поиск не удался: ${it.message}") }

                extraDiscoveries.forEach { discovery ->
                    runCatching { discovery.search() }
                        .onSuccess { found += it }
                        .onFailure { Log.w(TAG, "поиск ${discovery.kind} не удался: ${it.message}") }
                }
                _devices.value = found.distinctBy { it.id }
            } finally {
                _searching.value = false
            }
        }
    }

    fun connect(device: RemoteDevice) {
        val local = localPlayer?.invoke() ?: return
        val swap = onActivePlayerChanged ?: return
        val transport = runCatching { buildTransport(device) }
            .onFailure { _error.value = it.message ?: "не удалось подключиться к ${device.name}" }
            .getOrNull() ?: return

        val items = (0 until local.mediaItemCount).map { local.getMediaItemAt(it) }
        val index = local.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = local.currentPosition
        // Локальный звук глушим сразу: две копии одного трека (в комнате и из телефона) слышны
        // как эхо, а не как трансляция.
        local.pause()

        if (!startServer()) {
            _error.value = "не удалось поднять раздачу файлов"
            return
        }
        val address = localIpAddress()
        if (address == null) {
            _error.value = "нет Wi-Fi - трансляция без локальной сети невозможна"
            return
        }
        val base = "http://$address:$REMOTE_HTTP_PORT"
        val player = RemotePlayer(
            transport = transport,
            urlForItem = { item -> item.mediaId.takeIf { it.isNotEmpty() }?.let { "$base/track/$it.${extensionOf(item)}" } },
            mimeForItem = { mimeForFileName(it.localConfiguration?.uri?.lastPathSegment) },
            durationMsForItem = { item -> trackById?.invoke(item.mediaId)?.durationMs ?: 0L },
            onDisconnected = { disconnect() },
        )
        remotePlayer = player
        _connected.value = device
        swap(player)
        player.startWith(items, index, position)
    }

    fun disconnect() {
        val player = remotePlayer ?: return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        remotePlayer = null
        _connected.value = null
        server?.stop()
        server = null
        val local = localPlayer?.invoke()
        if (local != null) onActivePlayerChanged?.invoke(local)
        player.release()
        // Позицию с приёмника переносим обратно на телефон, но не возобновляем: "отключился от
        // телевизора" - это чаще "закончил слушать", чем "продолжаю в динамик".
        if (local != null && index != C.INDEX_UNSET && index < local.mediaItemCount) {
            local.seekTo(index, position)
        }
    }

    fun release() {
        disconnect()
        extraDiscoveries.forEach { it.release() }
        extraDiscoveries.clear()
        runCatching { DLNACast.cleanup() }
    }

    private fun buildTransport(device: RemoteDevice): RemoteTransport = when (device.kind) {
        RemoteKind.DLNA -> DlnaTransport(
            context = context,
            device = device,
            upnpDevice = upnpDevices[device.id] ?: error("устройство ${device.name} больше не отвечает, повторите поиск"),
        )
        else -> extraDiscoveries.firstOrNull { it.kind == device.kind }?.transportFor(device)
            ?: error("${device.name}: поддержка выключена в настройках")
    }

    private fun startServer(): Boolean {
        server?.stop()
        val fresh = LocalHttpServer(
            port = REMOTE_HTTP_PORT,
            trackByIdBlocking = { id -> trackById?.invoke(id) },
        )
        return runCatching { fresh.start(); server = fresh }
            .onFailure { Log.w(TAG, "раздача не поднялась: ${it.message}") }
            .isSuccess
    }

    /** Расширение в URL обязательно для Яндекс Станции (она отказывается качать ссылку без него)
     * и безвредно для остальных - у наших файлов имена вида UUID, точек в id не бывает. */
    private fun extensionOf(item: MediaItem): String =
        item.localConfiguration?.uri?.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: "mp3"
}

/** Искатель одной Beta-экосистемы. Живёт отдельно от [RemoteCastController], чтобы выключенный
 * тумблер означал "объект вообще не создан и сеть не сканируется", а не "спрятано в UI". */
interface RemoteDiscovery {
    val kind: RemoteKind
    suspend fun search(): List<RemoteDevice>
    fun transportFor(device: RemoteDevice): RemoteTransport
    fun release() = Unit
}
