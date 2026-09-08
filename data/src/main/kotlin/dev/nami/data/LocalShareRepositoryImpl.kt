package dev.nami.data

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.DiscoveredDevice
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListenTogetherGuestState
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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

    // ------------------------------------------------------------------ server

    override fun startServer() {
        if (_serverRunning.value) return
        val server = LocalHttpServer(
            port = SERVER_PORT,
            deviceName = android.os.Build.MODEL ?: "NAMI",
            trackByIdBlocking = { id -> runBlocking { libraryRepository.track(TrackId(id)).first() } },
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
        _serverAddress.value = "${localIpAddress()}:$SERVER_PORT"
        registerNsd()
    }

    override fun stopServer() {
        httpServer?.stop()
        httpServer = null
        _serverRunning.value = false
        _serverAddress.value = null
        unregisterNsd()
    }

    private fun localIpAddress(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
        return if (ipInt != 0) Formatter.formatIpAddress(ipInt) else "0.0.0.0"
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
