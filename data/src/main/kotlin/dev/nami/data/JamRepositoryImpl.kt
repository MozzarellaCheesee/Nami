package dev.nami.data

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.TrackId
import dev.nami.domain.JamRepository
import dev.nami.domain.JamSession
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlayerRepository
import dev.nami.domain.ServerAudioRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

private const val TAG = "JamRepository"
private const val RECONNECT_DELAY_MS = 3000L
private const val NSD_SERVICE_TYPE = "_nami-jam._tcp."

private data class TrackMeta(
    val streamUrl: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val format: String?,
)

@Singleton
class JamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val serverAudioRepository: ServerAudioRepository,
    private val syncRepository: SyncRepository,
) : JamRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<JamSession?>(null)
    override val session: StateFlow<JamSession?> = _session

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    private val _activeHostUrl = MutableStateFlow<String?>(null)
    override val activeHostUrl: StateFlow<String?> = _activeHostUrl

    override val isServerConfigured: StateFlow<Boolean> =
        combine(settingsRepository.namiServerToken, settingsRepository.namiServerUrl) { token, url ->
            !token.isNullOrBlank() && url.isNotBlank()
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            !settingsRepository.namiServerToken.value.isNullOrBlank() && settingsRepository.namiServerUrl.value.isNotBlank(),
        )

    override val allHostUrls: StateFlow<List<String>> =
        combine(settingsRepository.namiServerUrl, settingsRepository.namiServerToken) { url, _ ->
            url.split('\n', ',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val prefs = context.getSharedPreferences("nami_jam_prefs", Context.MODE_PRIVATE)
    private val _recentHosts = MutableStateFlow<List<String>>(loadRecentHosts())
    override val recentHosts: StateFlow<List<String>> = _recentHosts

    private fun loadRecentHosts(): List<String> {
        val raw = prefs.getString("recent_hosts", null) ?: return emptyList()
        return raw.split(";").map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
    }

    private fun saveRecentHost(host: String) {
        val clean = host.trim().trimEnd('/')
        if (clean.isBlank()) return
        val current = _recentHosts.value.filter { !it.equals(clean, ignoreCase = true) }
        val updated = (listOf(clean) + current).take(5)
        _recentHosts.value = updated
        prefs.edit().putString("recent_hosts", updated.joinToString(";")).apply()
    }

    @Volatile private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    @Volatile private var isConnecting = false
    private var reconnectJob: Job? = null
    @Volatile private var previousSession: JamSession? = null
    @Volatile private var intentionalClose = false
    private val pendingActions = mutableListOf<() -> Unit>()

    @Volatile private var currentBaseUrl: String? = null
    @Volatile private var guestServerUrl: String? = null
    @Volatile private var guestToken: String? = null
    @Volatile private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    override fun createRoom() {
        clearError()
        intentionalClose = false
        if (!isServerConfigured.value) {
            _error.value = "Для создания комнаты необходимо подключить сервер NAMI в Настройках"
            return
        }
        scope.launch {
            connectWebSocket(onOpened = {
                send(JSONObject().apply { put("type", "jam_create") })
            })
        }
    }

    override fun joinRoom(code: String, hostUrl: String?) {
        clearError()
        intentionalClose = false

        var parsedCode = code.trim()
        val candidateHosts = mutableListOf<String>()
        if (!hostUrl.isNullOrBlank()) {
            candidateHosts.addAll(hostUrl.split(',', '\n').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() })
        }

        // 1. Ссылки вида nami://jam?code=ABC234&host=...&hosts=host1,host2
        if (parsedCode.startsWith("nami://", ignoreCase = true) || parsedCode.contains("?code=") || parsedCode.contains("&code=")) {
            val uri = runCatching { Uri.parse(parsedCode) }.getOrNull()
            if (uri != null) {
                uri.getQueryParameter("code")?.let { parsedCode = it }
                uri.getQueryParameter("host")?.let { candidateHosts.add(it.trim().trimEnd('/')) }
                uri.getQueryParameter("hosts")?.split(',')?.forEach { candidateHosts.add(it.trim().trimEnd('/')) }
            }
        } else if (parsedCode.startsWith("http://", ignoreCase = true) || parsedCode.startsWith("https://", ignoreCase = true)) {
            // 2. Веб-ссылки вида http(s)://host:port/jam?code=ABC234 или /jam/ABC234
            val uri = runCatching { Uri.parse(parsedCode) }.getOrNull()
            if (uri != null) {
                val qCode = uri.getQueryParameter("code")
                if (!qCode.isNullOrBlank()) {
                    parsedCode = qCode
                } else {
                    val last = uri.lastPathSegment
                    if (!last.isNullOrBlank() && last != "jam") {
                        parsedCode = last
                    }
                }
                val base = "${uri.scheme}://${uri.authority}"
                candidateHosts.add(base)
                uri.getQueryParameter("host")?.let { candidateHosts.add(it.trim().trimEnd('/')) }
                uri.getQueryParameter("hosts")?.split(',')?.forEach { candidateHosts.add(it.trim().trimEnd('/')) }
            }
        } else if (parsedCode.contains("@")) {
            // 3. CODE@HOST
            val parts = parsedCode.split("@", limit = 2)
            parsedCode = parts[0].trim()
            candidateHosts.add(parts[1].trim().trimEnd('/'))
        }

        val trimmedCode = parsedCode.uppercase().filter { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }

        if (trimmedCode.length < 4) {
            _error.value = "Неверный код комнаты"
            return
        }

        val uniqueCandidates = candidateHosts.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

        scope.launch {
            if (uniqueCandidates.isNotEmpty()) {
                Log.d(TAG, "Connecting to Jam as guest with code $trimmedCode and candidates $uniqueCandidates")
                connectAsGuest(uniqueCandidates, trimmedCode)
            } else if (isServerConfigured.value) {
                Log.d(TAG, "Connecting to Jam via configured server with code $trimmedCode")
                connectWebSocket(onOpened = {
                    send(JSONObject().apply {
                        put("type", "jam_join")
                        put("code", trimmedCode)
                    })
                })
            } else {
                Log.d(TAG, "Searching for Jam $trimmedCode on Wi-Fi via NSD...")
                val discovered = discoverJamHostOnWifi(trimmedCode)
                if (discovered != null) {
                    Log.d(TAG, "Discovered Jam host on Wi-Fi: $discovered")
                    connectAsGuest(listOf(discovered), trimmedCode)
                } else {
                    // Пробуем проверить недавние серверы хостов
                    val recent = recentHosts.value
                    val fromRecent = if (recent.isNotEmpty()) probeReachableHost(recent, trimmedCode) else null
                    if (fromRecent != null) {
                        Log.d(TAG, "Found Jam room on recent host: ${fromRecent.first}")
                        connectAsGuestWithToken(fromRecent.first, fromRecent.second, trimmedCode)
                    } else {
                        _error.value = "Комната не найдена в локальной сети. Если организатор не в вашем Wi-Fi, вставьте ссылку на комнату или укажите адрес сервера."
                    }
                }
            }
        }
    }

    override fun leave() {
        intentionalClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        previousSession = null
        clearPendingActions()
        unregisterNsdJam()
        send(JSONObject().apply { put("type", "jam_leave") })
        try {
            webSocket?.close(1000, "leave")
        } catch (_: Exception) {}
        webSocket = null
        _session.value = null
        _connected.value = false
        _activeHostUrl.value = null
        guestServerUrl = null
        guestToken = null
    }

    override fun play(serverTrackId: Long, positionMs: Long) {
        val json = JSONObject().apply {
            put("type", "jam_play")
            put("track_id", serverTrackId)
            put("position_ms", positionMs)
            put("at", System.currentTimeMillis())
        }
        send(json)
    }

    override fun seek(positionMs: Long) {
        val currentTrackId = _session.value?.currentTrackId ?: return
        play(currentTrackId, positionMs)
    }

    override fun addToQueue(serverTrackId: Long) {
        val json = JSONObject().apply {
            put("type", "jam_queue_add")
            put("track_id", serverTrackId)
        }
        send(json)
    }

    override fun clearError() {
        _error.value = null
    }

    private fun send(json: JSONObject): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send message: WebSocket is null")
            return false
        }
        val text = json.toString()
        Log.d(TAG, "Sending WS message: $text")
        return ws.send(text)
    }

    private suspend fun probeReachableHost(hosts: List<String>, code: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val client = okHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val jsonReq = JSONObject().apply { put("code", code) }
        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (host in hosts) {
            val cleanHost = host.trim().trimEnd('/')
            val token = runCatching {
                val reqBody = jsonReq.toString().toRequestBody(mediaType)
                val authReq = Request.Builder()
                    .url("$cleanHost/api/jam/guest-auth")
                    .post(reqBody)
                    .build()
                client.newCall(authReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        val json = JSONObject(bodyStr)
                        json.optString("token").ifBlank { null }
                    } else null
                }
            }.getOrNull()

            if (token != null) {
                return@withContext cleanHost to token
            }
        }
        null
    }

    private suspend fun connectAsGuest(hostUrl: String, code: String) =
        connectAsGuest(listOf(hostUrl), code)

    private suspend fun connectAsGuest(hosts: List<String>, code: String) = withContext(Dispatchers.IO) {
        if (hosts.isEmpty()) {
            _error.value = "Не указан адрес сервера"
            return@withContext
        }

        val probed = probeReachableHost(hosts, code)
        val targetHost = probed?.first ?: hosts.first()
        val targetToken = probed?.second

        connectAsGuestWithToken(targetHost, targetToken, code)
    }

    private fun connectAsGuestWithToken(cleanHost: String, token: String?, code: String) {
        saveRecentHost(cleanHost)
        guestServerUrl = cleanHost
        _activeHostUrl.value = cleanHost
        guestToken = token

        val wsBase = when {
            cleanHost.startsWith("https://", ignoreCase = true) -> "wss://" + cleanHost.substring("https://".length)
            cleanHost.startsWith("http://", ignoreCase = true) -> "ws://" + cleanHost.substring("http://".length)
            else -> "ws://$cleanHost"
        }
        val wsUrl = if (token != null) {
            "$wsBase/api/ws?token=$token"
        } else {
            "$wsBase/api/ws?jam_code=$code"
        }

        startWebSocketConnection(wsBase, wsUrl, null, onOpened = {
            send(JSONObject().apply {
                put("type", "jam_join")
                put("code", code)
            })
        })
    }

    private suspend fun connectWebSocket(onOpened: (() -> Unit)? = null) = withContext(Dispatchers.IO) {
        if (onOpened != null) {
            synchronized(pendingActions) {
                pendingActions.add(onOpened)
            }
        }
        if (_connected.value && webSocket != null) {
            drainPendingActions()
            return@withContext
        }
        if (isConnecting) return@withContext

        isConnecting = true

        val token = settingsRepository.namiServerToken.value
        val cert = settingsRepository.namiServerCertSha256.value
        val urlSetting = settingsRepository.namiServerUrl.value
        val bases = urlSetting.split('\n', ',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

        if (token == null || bases.isEmpty()) {
            isConnecting = false
            _error.value = "Сервер NAMI не настроен или не авторизован"
            clearPendingActions()
            return@withContext
        }

        val reachable = NamiServerClient.reachableBase(bases, cert) ?: bases.first()
        currentBaseUrl = reachable
        _activeHostUrl.value = reachable
        val wsBase = when {
            reachable.startsWith("https://", ignoreCase = true) -> "wss://" + reachable.substring("https://".length)
            reachable.startsWith("http://", ignoreCase = true) -> "ws://" + reachable.substring("http://".length)
            else -> "ws://$reachable"
        }
        val wsUrl = "$wsBase/api/ws?token=$token"

        startWebSocketConnection(wsBase, wsUrl, cert, null)
    }

    private fun startWebSocketConnection(
        wsBase: String,
        wsUrl: String,
        certSha256: String?,
        onOpened: (() -> Unit)?,
    ) {
        try {
            webSocket?.close(1000, "reconnecting")
        } catch (_: Exception) {}
        webSocket = null

        try {
            val client = createOkHttpClient(wsBase, certSha256)
            okHttpClient = client
            val request = Request.Builder().url(wsUrl).build()

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "Jam WebSocket connected to $wsBase")
                    isConnecting = false
                    _connected.value = true
                    onOpened?.invoke()
                    drainPendingActions()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Jam WebSocket closed: $code / $reason")
                    isConnecting = false
                    handleDisconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Jam WebSocket failure: ${t.message}")
                    isConnecting = false
                    _error.value = "Ошибка соединения: ${t.message ?: "сервер недоступен"}"
                    handleDisconnect()
                }
            }

            webSocket = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Jam WebSocket: ${e.message}", e)
            isConnecting = false
            _error.value = "Ошибка подключения к Jam: ${e.message}"
            clearPendingActions()
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        _connected.value = false
        webSocket = null
        val current = _session.value
        if (current != null && !intentionalClose) {
            previousSession = current
            // Не обнуляем _session — UI показывает статус "Отключено" во время реконнекта
        } else if (current != null) {
            _session.value = null
        }
        if (!intentionalClose && previousSession != null) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (intentionalClose || previousSession == null) return@launch
            val sessionToRestore = previousSession ?: return@launch
            Log.d(TAG, "Attempting reconnect to Jam session ${sessionToRestore.code}")
            val host = guestServerUrl
            if (host != null) {
                connectAsGuest(host, sessionToRestore.code)
            } else {
                connectWebSocket(onOpened = {
                    send(JSONObject().apply {
                        put("type", "jam_join")
                        put("code", sessionToRestore.code)
                    })
                })
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "jam_created" -> {
                    val code = json.optString("code")
                    _session.value = JamSession(
                        code = code,
                        isHost = true,
                        queue = emptyList(),
                        currentTrackId = null,
                        positionMs = 0L,
                        lastSyncAt = 0L,
                        playedBy = null,
                    )
                    val host = currentBaseUrl ?: _activeHostUrl.value
                    if (host != null) {
                        registerNsdJam(code, host)
                    }
                }
                "jam_joined" -> {
                    val code = json.optString("code")
                    val queueArr = json.optJSONArray("queue")
                    val queue = mutableListOf<Long>()
                    if (queueArr != null) {
                        for (i in 0 until queueArr.length()) {
                            queue.add(queueArr.optLong(i))
                        }
                    }
                    val isHost = previousSession?.takeIf { it.code == code }?.isHost ?: false
                    previousSession = null
                    _session.value = JamSession(
                        code = code,
                        isHost = isHost,
                        queue = queue,
                        currentTrackId = null,
                        positionMs = 0L,
                        lastSyncAt = 0L,
                        playedBy = null,
                    )
                }
                "jam_left" -> {
                    _session.value = null
                    unregisterNsdJam()
                }
                "jam_play" -> {
                    val trackId = json.optLong("track_id")
                    val positionMs = json.optLong("position_ms", 0L)
                    val at = json.optLong("at", System.currentTimeMillis())
                    val playedBy = if (json.isNull("by")) null else json.optLong("by")
                    _session.value = _session.value?.copy(
                        currentTrackId = trackId,
                        positionMs = positionMs,
                        lastSyncAt = at,
                        playedBy = playedBy,
                    )
                    val latency = (System.currentTimeMillis() - at).coerceAtLeast(0L)
                    val adjustedPositionMs = (positionMs + latency).coerceAtLeast(0L)
                    scope.launch {
                        handleJamPlay(trackId, adjustedPositionMs)
                    }
                }
                "jam_queue" -> {
                    val queueArr = json.optJSONArray("queue")
                    val queue = mutableListOf<Long>()
                    if (queueArr != null) {
                        for (i in 0 until queueArr.length()) {
                            queue.add(queueArr.optLong(i))
                        }
                    }
                    _session.value = _session.value?.copy(queue = queue)
                }
                "jam_error" -> {
                    val errorMsg = json.optString("error")
                    _error.value = errorMsg.ifBlank { "Ошибка сервера Jam" }
                    previousSession = null
                }
                "changed" -> {
                    scope.launch { runCatching { syncRepository.pullFromServer() } }
                }
                "position" -> {
                    // Игнорируем сервисные сообщения
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse incoming WS message: ${e.message}", e)
        }
    }

    private suspend fun handleJamPlay(trackId: Long, adjustedPositionMs: Long) {
        val gHost = guestServerUrl
        val gToken = guestToken

        val meta = if (gHost != null) {
            val tokenParam = if (gToken != null) "?token=$gToken" else "?jam_code=${_session.value?.code.orEmpty()}"
            val sUrl = "$gHost/api/tracks/$trackId/stream/auto$tokenParam"

            var tTitle = "Трек #$trackId"
            var tArtist: String? = null
            var tDuration = 0L
            var tFormat: String? = null

            try {
                val detailUrl = "$gHost/api/tracks/$trackId$tokenParam"
                val req = Request.Builder().url(detailUrl).build()
                val client = okHttpClient ?: OkHttpClient()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val detail = JSONObject(body)
                        val trackObj = detail.optJSONObject("track")
                        tTitle = trackObj?.optString("title")?.ifBlank { null }
                            ?: detail.optString("title").ifBlank { null }
                            ?: "Трек #$trackId"
                        tArtist = trackObj?.optString("artist")?.ifBlank { null }
                            ?: detail.optString("artist").ifBlank { null }
                        tDuration = trackObj?.optLong("duration_ms", 0L)
                            ?: detail.optLong("duration_ms", 0L)
                            ?: 0L
                        tFormat = trackObj?.optString("format")?.ifBlank { null }
                            ?: detail.optString("format").ifBlank { null }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load guest track detail: ${e.message}")
            }
            TrackMeta(sUrl, tTitle, tArtist, tDuration, tFormat)
        } else {
            val cfg = activeConfig()
            val sUrl = serverAudioRepository.serverStreamUrl(trackId)
                ?: cfg?.let { "${it.baseUrl}/api/tracks/$trackId/stream/auto?token=${it.token}" }
                ?: return

            val detail = cfg?.let { NamiServerClient.trackDetail(it, trackId) }
            val trackObj = detail?.optJSONObject("track")
            val tTitle = trackObj?.optString("title")?.ifBlank { null }
                ?: detail?.optString("title")?.ifBlank { null }
                ?: "Трек #$trackId"
            val tArtist = trackObj?.optString("artist")?.ifBlank { null }
                ?: detail?.optString("artist")?.ifBlank { null }
            val tDuration = trackObj?.optLong("duration_ms", 0L)
                ?: detail?.optLong("duration_ms", 0L)
                ?: 0L
            val tFormat = trackObj?.optString("format")?.ifBlank { null }
                ?: detail?.optString("format")?.ifBlank { null }

            TrackMeta(sUrl, tTitle, tArtist, tDuration, tFormat)
        }

        val playable = PlayableTrack(
            id = TrackId("jam_$trackId"),
            title = meta.title,
            artistName = meta.artist,
            path = meta.streamUrl,
            format = meta.format,
            durationMs = meta.durationMs,
        )

        withContext(Dispatchers.Main) {
            playerRepository.play(listOf(playable), startIndex = 0, startMs = adjustedPositionMs)
        }
    }

    private fun registerNsdJam(code: String, host: String) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        try {
            unregisterNsdJam()
            val uri = runCatching { URI(if (host.contains("://")) host else "http://$host") }.getOrNull()
            val port = uri?.port?.takeIf { it > 0 } ?: 4533
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "NamiJam-$code"
                serviceType = NSD_SERVICE_TYPE
                setPort(port)
                setAttribute("code", code)
                setAttribute("host", host)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(TAG, "NSD Jam registered: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "NSD Jam registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            }
            nsdRegistrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NSD Jam service: ${e.message}", e)
        }
    }

    private fun unregisterNsdJam() {
        val listener = nsdRegistrationListener ?: return
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (_: Exception) {}
        nsdRegistrationListener = null
    }

    private suspend fun discoverJamHostOnWifi(code: String): String? = withTimeoutOrNull(2500L) {
        suspendCancellableCoroutine { continuation ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("_nami-jam") || serviceInfo.serviceName.contains("NamiJam-$code")) {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val attrCode = resolved.attributes["code"]?.let { String(it) }
                                val attrHost = resolved.attributes["host"]?.let { String(it) }
                                if (attrCode.equals(code, ignoreCase = true) && !attrHost.isNullOrBlank()) {
                                    if (continuation.isActive) continuation.resume(attrHost)
                                } else if (serviceInfo.serviceName.contains(code, ignoreCase = true)) {
                                    val host = "http://${resolved.host.hostAddress}:${resolved.port}"
                                    if (continuation.isActive) continuation.resume(host)
                                }
                            }
                        })
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            continuation.invokeOnCancellation {
                try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            }
            try {
                nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun activeConfig(): NamiServerClient.Config? {
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        val bases = settingsRepository.namiServerUrl.value
            .split('\n', ',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        if (bases.isEmpty()) return null
        val base = currentBaseUrl ?: NamiServerClient.reachableBase(bases, cert) ?: bases.first()
        return NamiServerClient.Config(base, token, cert, bases)
    }

    private fun createOkHttpClient(wsBase: String, certSha256: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)

        val host = runCatching {
            val probeUrl = if (wsBase.contains("://")) wsBase else "ws://$wsBase"
            URI(probeUrl.replace("^wss?".toRegex(), "http")).host
        }.getOrNull().orEmpty()

        val isTls = wsBase.startsWith("wss://", ignoreCase = true) || wsBase.startsWith("https://", ignoreCase = true)

        if (isTls && certSha256 != null && hostIsIpLiteral(host)) {
            val tm = pinnedTrustManager(certSha256)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(tm), null)
            }
            builder.sslSocketFactory(sslContext.socketFactory, tm)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    private fun hostIsIpLiteral(host: String): Boolean {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        return cleanHost.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || cleanHost.contains(':')
    }

    private fun pinnedTrustManager(expected: String): X509TrustManager {
        val want = expected.removePrefix("sha256:").lowercase()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("сервер не прислал сертификат")
                val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (fp != want) throw CertificateException("отпечаток сертификата сервера не совпал")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    private fun drainPendingActions() {
        val actions = synchronized(pendingActions) {
            val list = pendingActions.toList()
            pendingActions.clear()
            list
        }
        actions.forEach { action ->
            try {
                action.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Error executing pending action: ${e.message}", e)
            }
        }
    }

    private fun clearPendingActions() {
        synchronized(pendingActions) {
            pendingActions.clear()
        }
    }
}
