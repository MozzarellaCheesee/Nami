package dev.nami.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.DiscoveredDevice
import dev.nami.domain.ImportSource
import dev.nami.domain.InternetLinkState
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListenTogetherGuestState
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.WifiDirectPeer
import dev.nami.player.LocalHttpServer
import dev.nami.player.localIpAddress
import dev.nami.data.webrtc.WebRtcInternetLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalShareRepository"
private const val SERVICE_TYPE = "_nami._tcp."
private const val SERVER_PORT = 47821
private const val POLL_INTERVAL_MS = 1500L
private const val PREFETCH_COUNT = 2
private const val MATCH_DURATION_TOLERANCE_MS = 2000L
private const val DRIFT_THRESHOLD_MS = 1500L

@Singleton
class LocalShareRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
    private val playlistRepository: PlaylistRepository,
) : LocalShareRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var httpServer: LocalHttpServer? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _serverRunning = MutableStateFlow(false)
    override val serverRunning: StateFlow<Boolean> = _serverRunning
    private val _serverAddress = MutableStateFlow<String?>(null)
    override val serverAddress: StateFlow<String?> = _serverAddress

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    private val _dropTrack = MutableStateFlow<Track?>(null)
    override val dropTrack: StateFlow<Track?> = _dropTrack

    private val _listenTogetherHostEnabled = MutableStateFlow(false)
    override val listenTogetherHostEnabled: StateFlow<Boolean> = _listenTogetherHostEnabled

    private val _listenTogetherGuestState = MutableStateFlow<ListenTogetherGuestState?>(null)
    override val listenTogetherGuestState: StateFlow<ListenTogetherGuestState?> = _listenTogetherGuestState
    private var guestJob: Job? = null
    private val listenTogetherCacheDir get() = File(context.cacheDir, "listen_together").apply { mkdirs() }
    private val cachedFilesByTrackId = mutableMapOf<String, File>()

    // ------------------------------------------------------------------ Wi-Fi Direct
    private val wifiP2pManager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiP2pReceiver: BroadcastReceiver? = null
    private val _wifiDirectPeers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    override val wifiDirectPeers: StateFlow<List<WifiDirectPeer>> = _wifiDirectPeers
    private val _wifiDirectConnecting = MutableStateFlow(false)
    override val wifiDirectConnecting: StateFlow<Boolean> = _wifiDirectConnecting
    private val _wifiDirectConnected = MutableStateFlow(false)
    override val wifiDirectConnected: StateFlow<Boolean> = _wifiDirectConnected
    // Имя peer'а, к которому только что запросили connect() - broadcast о смене соединения не
    // несёт имени, только его MAC/адрес и networkInfo, так что берём имя отсюда для DiscoveredDevice.
    private var pendingWifiDirectPeerName: String? = null
    // Host, добавленный в discoveredDevices при подключении по Wi-Fi Direct - нужен, чтобы убрать
    // его же при разрыве группы (см. WIFI_P2P_CONNECTION_CHANGED_ACTION ниже).
    private var wifiDirectConnectedHost: String? = null

    // ------------------------------------------------------------------ WebRTC интернет-мост
    private var webRtcLink: WebRtcInternetLink? = null
    private var internetLinkIsHost = false
    private var internetLinkPushJob: Job? = null
    private val _internetLinkState = MutableStateFlow(InternetLinkState.IDLE)
    override val internetLinkState: StateFlow<InternetLinkState> = _internetLinkState
    private val _internetInviteCode = MutableStateFlow<String?>(null)
    override val internetInviteCode: StateFlow<String?> = _internetInviteCode
    private val _internetAnswerCode = MutableStateFlow<String?>(null)
    override val internetAnswerCode: StateFlow<String?> = _internetAnswerCode
    // Один трек скачивается за раз (см. WebRtcInternetLink doc) - гостевая сторона копит куски
    // сюда между onTrackMeta и onTrackEnd.
    private var pendingTrackId: String? = null
    private var pendingTrackFileName: String? = null
    private var pendingTrackBuffer: ByteArrayOutputStream? = null

    init {
        // Пока раздача (Wi-Fi Drop) запущена (dropTrack != null), она следует за играющим
        // треком автоматически - раньше setDropTrack() вызывался только один раз вручную и
        // раздача навсегда замирала на том треке, даже когда хост давно переключился на другой.
        scope.launch {
            playerRepository.queue.map { it.nowPlaying?.id }.distinctUntilChanged().collect { id ->
                if (_dropTrack.value == null) return@collect
                _dropTrack.value = id?.let { runCatching { libraryRepository.track(it).first() }.getOrNull() }
            }
        }
    }

    // ------------------------------------------------------------------ server

    override fun startServer() {
        if (_serverRunning.value) return
        val server = LocalHttpServer(
            port = SERVER_PORT,
            trackByIdBlocking = { id -> runBlocking { libraryRepository.track(TrackId(id)).first() } },
            deviceName = android.os.Build.MODEL ?: "NAMI",
            nowPlayingJsonBlocking = { if (_listenTogetherHostEnabled.value) buildNowPlayingJson() else null },
            dropTrackBlocking = { _dropTrack.value },
            manifestJsonBlocking = { runBlocking { buildSyncManifest() } },
        )
        try {
            server.start()
        } catch (e: Exception) {
            Log.w(TAG, "server start failed: ${e.message}")
            return
        }
        httpServer = server
        _serverRunning.value = true
        _serverAddress.value = "${localIpAddress(context)}:$SERVER_PORT"
        registerNsd()
    }

    override fun stopServer() {
        httpServer?.stop()
        httpServer = null
        _serverRunning.value = false
        _serverAddress.value = null
        unregisterNsd()
    }

    private fun buildNowPlayingJson(): JSONObject? {
        val playing = playerRepository.state.value as? PlaybackState.Playing ?: return null
        val queue = playerRepository.queue.value
        val nowPlaying = queue.nowPlaying ?: return null
        val upcoming = JSONArray()
        queue.upcoming.take(PREFETCH_COUNT).forEach { upcoming.put(it.track.id.value) }
        return JSONObject().apply {
            put("trackId", nowPlaying.id.value)
            put("title", nowPlaying.title)
            put("artistName", nowPlaying.artistName ?: JSONObject.NULL)
            put("positionMs", playing.positionMs)
            put("durationMs", playing.durationMs)
            put("isPlaying", playing.isPlaying)
            put("upcoming", upcoming)
        }
    }

    private suspend fun buildSyncManifest(): JSONObject {
        val tracks = JSONArray()
        libraryRepository.allTracksOrdered().forEach { track ->
            val liked = playlistRepository.isTrackLiked(track.id).first()
            if (track.rating == null && !liked) return@forEach // nothing worth syncing for this track
            tracks.put(
                JSONObject().apply {
                    put("title", track.title)
                    put("artistName", track.artistName ?: JSONObject.NULL)
                    put("durationMs", track.durationMs)
                    put("rating", track.rating ?: JSONObject.NULL)
                    put("liked", liked)
                },
            )
        }
        return JSONObject().put("tracks", tracks)
    }

    // ------------------------------------------------------------------ NSD discovery

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = "NAMI-${android.os.Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = SERVER_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD register failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD register threw: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    override fun startDiscovery() {
        if (discoveryListener != null) return
        _discoveredDevices.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                nsdManager.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            // Discovery runs on the same device that's also registering/serving -
                            // without this, every device sees its own broadcast in the list.
                            if (host == localIpAddress(context)) return
                            val device = DiscoveredDevice(name = info.serviceName, host = host, port = info.port)
                            _discoveredDevices.value = (_discoveredDevices.value.filterNot { it.host == host } + device)
                        }
                    },
                )
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                _discoveredDevices.value = _discoveredDevices.value.filterNot { it.name == service.serviceName }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery threw: ${e.message}")
        }
    }

    override fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    override fun parseManualAddress(text: String): DiscoveredDevice? {
        // Принимает как голое "192.168.1.5:47821" (со сканера QR или вбитое руками), так и
        // полный "nami://192.168.1.5:47821" - на случай если QR когда-нибудь начнёт нести схему.
        val stripped = text.removePrefix("nami://").trim()
        val parts = stripped.split(":")
        if (parts.size != 2) return null
        val port = parts[1].toIntOrNull() ?: return null
        return DiscoveredDevice(name = stripped, host = parts[0], port = port)
    }

    // ------------------------------------------------------------------ Wi-Fi Drop

    override fun setDropTrack(track: Track?) {
        _dropTrack.value = track
    }

    override suspend fun pullDrop(device: DiscoveredDevice): Boolean {
        val (bytes, fileName) = httpDownload(device, "/drop") ?: return false
        val scratchDir = File(context.cacheDir, "wifi_drop").apply { mkdirs() }
        val scratchFile = File(scratchDir, "${UUID.randomUUID()}_$fileName")
        scratchFile.writeBytes(bytes)
        return try {
            libraryRepository.import(ImportSource.Files(listOf(Uri.fromFile(scratchFile).toString()))).collect { }
            true
        } catch (e: Exception) {
            false
        } finally {
            scratchFile.delete()
        }
    }

    // ------------------------------------------------------------------ синхронизация

    override suspend fun syncWith(device: DiscoveredDevice): Int {
        val json = httpGetJson(device, "/manifest") ?: return 0
        val remoteTracks = json.optJSONArray("tracks") ?: return 0
        val localTracks = libraryRepository.allTracksOrdered()
        var updated = 0
        for (i in 0 until remoteTracks.length()) {
            val remote = remoteTracks.getJSONObject(i)
            val title = remote.optString("title")
            val artist = remote.optString("artistName", null)
            val duration = remote.optLong("durationMs")
            val match = localTracks.firstOrNull {
                it.title.equals(title, ignoreCase = true) &&
                    (it.artistName ?: "").equals(artist ?: "", ignoreCase = true) &&
                    kotlin.math.abs(it.durationMs - duration) <= MATCH_DURATION_TOLERANCE_MS
            } ?: continue

            var changed = false
            if (!remote.isNull("rating")) {
                val remoteRating = remote.optInt("rating")
                if (match.rating != remoteRating) {
                    libraryRepository.setTrackRating(match.id, remoteRating)
                    changed = true
                }
            }
            if (remote.optBoolean("liked") && !playlistRepository.isTrackLiked(match.id).first()) {
                playlistRepository.likeTrack(match.id)
                changed = true
            }
            if (changed) updated++
        }
        return updated
    }

    // ------------------------------------------------------------------ слушать вместе

    override fun setListenTogetherHost(enabled: Boolean) {
        _listenTogetherHostEnabled.value = enabled
    }

    override fun joinListenTogether(device: DiscoveredDevice) {
        leaveListenTogether()
        val hostName = runBlocking { httpGetJson(device, "/info")?.optString("name") } ?: device.name
        guestJob = scope.launch {
            while (true) {
                val json = httpGetJson(device, "/nowplaying")
                if (json == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                val trackId = json.optString("trackId")
                val current = _listenTogetherGuestState.value
                if (current?.trackId?.value != trackId) {
                    _listenTogetherGuestState.value = ListenTogetherGuestState(
                        hostName = hostName,
                        trackId = TrackId(trackId),
                        trackTitle = json.optString("title"),
                        artistName = json.optString("artistName", null),
                        downloading = cachedFilesByTrackId[trackId] == null,
                        cachedPath = cachedFilesByTrackId[trackId]?.path,
                        positionMs = json.optLong("positionMs"),
                        durationMs = json.optLong("durationMs"),
                    )
                    val cached = cachedFilesByTrackId[trackId]
                    if (cached != null) {
                        playCached(cached, json.optLong("positionMs"))
                    } else {
                        downloadAndPlay(device, trackId, json.optString("title"), json.optString("artistName", null), json.optLong("positionMs"))
                    }
                } else {
                    _listenTogetherGuestState.value = current.copy(positionMs = json.optLong("positionMs"), durationMs = json.optLong("durationMs"))
                    correctDrift(json.optLong("positionMs"), json.optBoolean("isPlaying", true))
                }

                // Prefetch what's coming up so switching doesn't wait on a download.
                val upcoming = json.optJSONArray("upcoming")
                if (upcoming != null) {
                    for (i in 0 until upcoming.length()) {
                        val id = upcoming.getString(i)
                        if (cachedFilesByTrackId[id] == null) {
                            scope.launch { downloadOnly(device, id) }
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun leaveListenTogether() {
        guestJob?.cancel()
        guestJob = null
        // "Выйти" должен закрывать и интернет-мост, если гость слушал через него, а не только
        // LAN-сессию - иначе канал остаётся висеть открытым, а гостевой стейт не сбрасывается
        // до следующего onChannelClosed.
        if (webRtcLink != null && !internetLinkIsHost) closeInternetLink()
        _listenTogetherGuestState.value = null
        cachedFilesByTrackId.clear()
        listenTogetherCacheDir.deleteRecursively()
    }

    override suspend fun addCurrentListenTogetherTrackToLibrary(): Boolean {
        val path = _listenTogetherGuestState.value?.cachedPath ?: return false
        return try {
            libraryRepository.import(ImportSource.Files(listOf(Uri.fromFile(File(path)).toString()))).collect { }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun downloadAndPlay(device: DiscoveredDevice, trackId: String, title: String, artistName: String?, startPositionMs: Long) {
        val file = downloadOnly(device, trackId) ?: return
        // Only actually play it if the guest hasn't since moved on to a different track while
        // this download was in flight.
        if (_listenTogetherGuestState.value?.trackId?.value != trackId) return
        _listenTogetherGuestState.value = _listenTogetherGuestState.value?.copy(downloading = false, cachedPath = file.path)
        playCached(file, startPositionMs)
    }

    private suspend fun downloadOnly(device: DiscoveredDevice, trackId: String): File? {
        if (cachedFilesByTrackId[trackId] != null) return cachedFilesByTrackId[trackId]
        val (bytes, fileName) = httpDownload(device, "/track/$trackId") ?: return null
        val file = File(listenTogetherCacheDir, "${trackId}_$fileName")
        file.writeBytes(bytes)
        cachedFilesByTrackId[trackId] = file
        return file
    }

    /** Непрерывная синхронизация позиции гостя с хостом - каждый тик поллинга (см.
     * joinListenTogether) сверяет свою текущую позицию с хостовой и корректирует seek'ом при
     * заметном расхождении. Честно не пытается быть sample-accurate (никакой общей тактовой
     * частоты между устройствами нет, это HTTP-поллинг раз в POLL_INTERVAL_MS, не медиа-протокол
     * реального времени) - порог DRIFT_THRESHOLD_MS специально широкий, чтобы не дёргать seek на
     * каждый обычный джиттер сети. */
    private suspend fun correctDrift(hostPositionMs: Long, hostIsPlaying: Boolean) {
        val playing = playerRepository.state.value as? PlaybackState.Playing ?: return
        if (playing.isPlaying != hostIsPlaying) {
            playerRepository.toggle()
        }
        val drift = playing.positionMs - hostPositionMs
        if (kotlin.math.abs(drift) > DRIFT_THRESHOLD_MS) {
            playerRepository.seek(hostPositionMs)
        }
    }

    private suspend fun playCached(file: File, startPositionMs: Long) {
        playerRepository.play(
            listOf(
                dev.nami.domain.PlayableTrack(
                    id = TrackId(file.nameWithoutExtension),
                    title = _listenTogetherGuestState.value?.trackTitle ?: file.nameWithoutExtension,
                    artistName = _listenTogetherGuestState.value?.artistName,
                    path = file.path,
                ),
            ),
            startIndex = 0,
            startMs = startPositionMs,
        )
    }

    // ------------------------------------------------------------------ Wi-Fi Direct

    /** Работает без общей Wi-Fi сети и без интернета - устройства сами договариваются о своей
     * IP-подсети (обычно 192.168.49.x). Подключённый peer добавляется в [_discoveredDevices] как
     * обычное устройство (см. [connectWifiDirect]) - весь остальной код (Wi-Fi Drop, синхронизация,
     * "слушать вместе") работает с ним без единого изменения, потому что ServerSocket уже слушает
     * на всех интерфейсах, не только на обычной Wi-Fi. */
    override fun startWifiDirectDiscovery() {
        val manager = wifiP2pManager ?: return
        if (wifiP2pChannel == null) wifiP2pChannel = manager.initialize(context, context.mainLooper, null)
        val channel = wifiP2pChannel ?: return

        if (wifiP2pReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            manager.requestPeers(channel) { peers ->
                                _wifiDirectPeers.value = peers.deviceList.map {
                                    WifiDirectPeer(name = it.deviceName, address = it.deviceAddress, status = peerStatusLabel(it.status))
                                }
                            }
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                            if (networkInfo?.isConnected == true) {
                                manager.requestConnectionInfo(channel) { info ->
                                    _wifiDirectConnecting.value = false
                                    // Мы сами группа-владелец - другая сторона подключится к нашему же
                                    // ServerSocket по нашему адресу, добавлять самих себя незачем.
                                    if (info.groupFormed) _wifiDirectConnected.value = true
                                    if (info.groupFormed && !info.isGroupOwner) {
                                        val host = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                                        val name = pendingWifiDirectPeerName ?: "Wi-Fi Direct"
                                        wifiDirectConnectedHost = host
                                        _discoveredDevices.value = (_discoveredDevices.value.filterNot { it.host == host } +
                                            DiscoveredDevice(name = name, host = host, port = SERVER_PORT))
                                    }
                                    // Владелец группы не знает IP клиента через этот API вообще
                                    // (WifiP2pInfo его не отдаёт) - вместо ручного угадывания
                                    // перезапускаем уже рабочий NSD-автопоиск, тот же сокет
                                    // слушает на всех интерфейсах, включая интерфейс p2p-группы,
                                    // так что обе стороны находят друг друга обычным mDNS поверх
                                    // новой подсети без отдельного кода под каждую роль.
                                    stopDiscovery()
                                    startDiscovery()
                                }
                            } else {
                                // Группа распалась (вышли/переключились/собеседник ушёл) - раньше
                                // устройство так и оставалось в списке навсегда, будто всё ещё
                                // подключено.
                                wifiDirectConnectedHost?.let { host ->
                                    _discoveredDevices.value = _discoveredDevices.value.filterNot { it.host == host }
                                }
                                wifiDirectConnectedHost = null
                                _wifiDirectConnecting.value = false
                                _wifiDirectConnected.value = false
                            }
                        }
                    }
                }
            }
            wifiP2pReceiver = receiver
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                },
            )
        }
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { Log.w(TAG, "Wi-Fi Direct discoverPeers failed: $reason") }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi Direct discoverPeers denied: ${e.message}")
        }
    }

    override fun stopWifiDirectDiscovery() {
        wifiP2pReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        wifiP2pReceiver = null
        wifiP2pChannel?.let { channel -> runCatching { wifiP2pManager?.stopPeerDiscovery(channel, null) } }
        _wifiDirectPeers.value = emptyList()
    }

    override fun connectWifiDirect(peer: WifiDirectPeer) {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        pendingWifiDirectPeerName = peer.name
        _wifiDirectConnecting.value = true
        val config = WifiP2pConfig().apply { deviceAddress = peer.address }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    _wifiDirectConnecting.value = false
                    Log.w(TAG, "Wi-Fi Direct connect failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            _wifiDirectConnecting.value = false
            Log.w(TAG, "Wi-Fi Direct connect denied: ${e.message}")
        }
    }

    override fun disconnectWifiDirect() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { Log.w(TAG, "Wi-Fi Direct removeGroup failed: $reason") }
        })
        // removeGroup обычно доводит до нас же WIFI_P2P_CONNECTION_CHANGED_ACTION(isConnected=false)
        // асинхронно, но сбрасываем сразу - UI не должен ждать broadcast, чтобы не мигать
        // "подключено" ещё секунду после явного нажатия "Отключить".
        wifiDirectConnectedHost?.let { host -> _discoveredDevices.value = _discoveredDevices.value.filterNot { it.host == host } }
        wifiDirectConnectedHost = null
        _wifiDirectConnected.value = false
        _wifiDirectConnecting.value = false
    }

    private fun peerStatusLabel(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "подключено"
        WifiP2pDevice.INVITED -> "приглашение отправлено"
        WifiP2pDevice.FAILED -> "ошибка"
        WifiP2pDevice.AVAILABLE -> "доступно"
        WifiP2pDevice.UNAVAILABLE -> "недоступно"
        else -> "неизвестно"
    }

    // ------------------------------------------------------------------ WebRTC интернет-мост

    override suspend fun createInternetInvite(): String {
        closeInternetLink()
        val link = WebRtcInternetLink(context)
        webRtcLink = link
        internetLinkIsHost = true
        _internetLinkState.value = InternetLinkState.CONNECTING
        wireInternetLinkCallbacks(link)
        return link.createInvite().also { _internetInviteCode.value = it }
    }

    override suspend fun acceptInternetInvite(inviteCode: String): String {
        closeInternetLink()
        val link = WebRtcInternetLink(context)
        webRtcLink = link
        internetLinkIsHost = false
        _internetLinkState.value = InternetLinkState.CONNECTING
        wireInternetLinkCallbacks(link)
        return link.acceptInvite(inviteCode).also { _internetAnswerCode.value = it }
    }

    override suspend fun completeInternetLink(answerCode: String) {
        webRtcLink?.completeLink(answerCode)
    }

    override fun closeInternetLink() {
        internetLinkPushJob?.cancel()
        internetLinkPushJob = null
        webRtcLink?.close()
        webRtcLink = null
        _internetLinkState.value = InternetLinkState.IDLE
        _internetInviteCode.value = null
        _internetAnswerCode.value = null
        if (!internetLinkIsHost) _listenTogetherGuestState.value = null
    }

    private fun wireInternetLinkCallbacks(link: WebRtcInternetLink) {
        link.onChannelOpen = {
            _internetLinkState.value = InternetLinkState.CONNECTED
            // Хост пушит nowplaying сам (не по запросу) - тот же переключатель "показывать что
            // играю" (listenTogetherHostEnabled), что и у LAN-версии, интервал тот же (POLL_INTERVAL_MS).
            if (internetLinkIsHost) {
                internetLinkPushJob = scope.launch {
                    while (isActive) {
                        if (_listenTogetherHostEnabled.value) {
                            buildNowPlayingJson()?.let { link.sendText(it.toString()) }
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
        }
        link.onChannelClosed = {
            _internetLinkState.value = InternetLinkState.FAILED
            internetLinkPushJob?.cancel()
        }
        if (internetLinkIsHost) {
            link.onTrackRequest = { trackId -> scope.launch { serveTrackOverLink(link, trackId) } }
        } else {
            link.onTextMessage = { text -> scope.launch { handleInternetNowPlaying(link, JSONObject(text)) } }
            link.onTrackMeta = { trackId, fileName, totalBytes ->
                pendingTrackId = trackId
                pendingTrackFileName = fileName
                pendingTrackBuffer = ByteArrayOutputStream(totalBytes.coerceAtLeast(0))
            }
            link.onTrackChunk = { chunk -> pendingTrackBuffer?.write(chunk) }
            link.onTrackEnd = {
                val buffer = pendingTrackBuffer
                val id = pendingTrackId
                val fileName = pendingTrackFileName
                pendingTrackBuffer = null
                if (buffer != null && id != null && fileName != null) {
                    val file = File(listenTogetherCacheDir, "${id}_$fileName")
                    file.writeBytes(buffer.toByteArray())
                    cachedFilesByTrackId[id] = file
                    scope.launch {
                        if (_listenTogetherGuestState.value?.trackId?.value == id) {
                            _listenTogetherGuestState.value = _listenTogetherGuestState.value?.copy(downloading = false, cachedPath = file.path)
                            playCached(file, _listenTogetherGuestState.value?.positionMs ?: 0L)
                        }
                    }
                }
            }
        }
    }

    private suspend fun serveTrackOverLink(link: WebRtcInternetLink, trackId: String) {
        val track = libraryRepository.track(TrackId(trackId)).first() ?: return
        val file = File(track.path)
        if (!file.exists()) return
        link.sendTrackMeta(trackId, file.name, file.length().toInt())
        link.sendTrackBytes(file.readBytes())
    }

    private suspend fun handleInternetNowPlaying(link: WebRtcInternetLink, json: JSONObject) {
        val trackId = json.optString("trackId")
        val current = _listenTogetherGuestState.value
        if (current?.trackId?.value != trackId) {
            _listenTogetherGuestState.value = ListenTogetherGuestState(
                hostName = current?.hostName ?: "Интернет",
                trackId = TrackId(trackId),
                trackTitle = json.optString("title"),
                artistName = json.optString("artistName", null),
                downloading = cachedFilesByTrackId[trackId] == null,
                cachedPath = cachedFilesByTrackId[trackId]?.path,
                positionMs = json.optLong("positionMs"),
                durationMs = json.optLong("durationMs"),
            )
            val cached = cachedFilesByTrackId[trackId]
            if (cached != null) playCached(cached, json.optLong("positionMs")) else link.sendTrackRequest(trackId)
        } else {
            _listenTogetherGuestState.value = current.copy(positionMs = json.optLong("positionMs"), durationMs = json.optLong("durationMs"))
            correctDrift(json.optLong("positionMs"), json.optBoolean("isPlaying", true))
        }
    }

    // ------------------------------------------------------------------ HTTP client helpers

    private fun httpGetJson(device: DiscoveredDevice, path: String): JSONObject? = try {
        val conn = URL("http://${device.host}:${device.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        if (conn.responseCode !in 200..299) {
            null
        } else {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET $path failed: ${e.message}")
        null
    }

    private fun httpDownload(device: DiscoveredDevice, path: String): Pair<ByteArray, String>? = try {
        val conn = URL("http://${device.host}:${device.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 20_000
        if (conn.responseCode !in 200..299) {
            null
        } else {
            val fileName = conn.getHeaderField("X-Original-Filename") ?: "track.audio"
            conn.inputStream.use { it.readBytes() } to fileName
        }
    } catch (e: Exception) {
        Log.w(TAG, "download $path failed: ${e.message}")
        null
    }
}
