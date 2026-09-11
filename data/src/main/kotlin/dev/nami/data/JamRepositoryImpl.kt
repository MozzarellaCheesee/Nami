package dev.nami.data

import android.util.Log
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

private const val TAG = "JamRepository"
private const val RECONNECT_DELAY_MS = 3000L

@Singleton
class JamRepositoryImpl @Inject constructor(
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

    override val isServerConfigured: StateFlow<Boolean> =
        combine(settingsRepository.namiServerToken, settingsRepository.namiServerUrl) { token, url ->
            !token.isNullOrBlank() && url.isNotBlank()
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            !settingsRepository.namiServerToken.value.isNullOrBlank() && settingsRepository.namiServerUrl.value.isNotBlank(),
        )

    @Volatile private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    @Volatile private var isConnecting = false
    private var reconnectJob: Job? = null
    @Volatile private var previousSession: JamSession? = null
    @Volatile private var intentionalClose = false
    private val pendingActions = mutableListOf<() -> Unit>()

    @Volatile
    private var currentBaseUrl: String? = null

    override fun createRoom() {
        clearError()
        intentionalClose = false
        scope.launch {
            connectWebSocket {
                send(JSONObject().apply { put("type", "jam_create") })
            }
        }
    }

    override fun joinRoom(code: String) {
        clearError()
        intentionalClose = false
        val trimmed = code.trim().uppercase()
        scope.launch {
            connectWebSocket {
                send(JSONObject().apply {
                    put("type", "jam_join")
                    put("code", trimmed)
                })
            }
        }
    }

    override fun leave() {
        intentionalClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        previousSession = null
        clearPendingActions()
        send(JSONObject().apply { put("type", "jam_leave") })
        try {
            webSocket?.close(1000, "leave")
        } catch (_: Exception) {}
        webSocket = null
        _session.value = null
        _connected.value = false
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
        val wsBase = when {
            reachable.startsWith("https://", ignoreCase = true) -> "wss://" + reachable.substring("https://".length)
            reachable.startsWith("http://", ignoreCase = true) -> "ws://" + reachable.substring("http://".length)
            else -> "ws://$reachable"
        }
        val wsUrl = "$wsBase/api/ws?token=$token"

        try {
            webSocket?.close(1000, "reconnecting")
        } catch (_: Exception) {}
        webSocket = null

        try {
            val client = createOkHttpClient(wsBase, cert)
            okHttpClient = client
            val request = Request.Builder().url(wsUrl).build()

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "Jam WebSocket connected to $wsBase")
                    isConnecting = false
                    _connected.value = true
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
            connectWebSocket {
                send(JSONObject().apply {
                    put("type", "jam_join")
                    put("code", sessionToRestore.code)
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
        val cfg = activeConfig()
        val streamUrl = serverAudioRepository.serverStreamUrl(trackId)
            ?: cfg?.let { "${it.baseUrl}/api/tracks/$trackId/stream/auto?token=${it.token}" }
            ?: return

        val detail = cfg?.let { NamiServerClient.trackDetail(it, trackId) }
        val trackObj = detail?.optJSONObject("track")
        val title = trackObj?.optString("title")?.ifBlank { null }
            ?: detail?.optString("title")?.ifBlank { null }
            ?: "Трек #$trackId"
        val artist = trackObj?.optString("artist")?.ifBlank { null }
            ?: detail?.optString("artist")?.ifBlank { null }
        val durationMs = trackObj?.optLong("duration_ms", 0L)
            ?: detail?.optLong("duration_ms", 0L)
            ?: 0L
        val format = trackObj?.optString("format")?.ifBlank { null }
            ?: detail?.optString("format")?.ifBlank { null }

        val playable = PlayableTrack(
            id = TrackId("server_$trackId"),
            title = title,
            artistName = artist,
            path = streamUrl,
            format = format,
            durationMs = durationMs,
        )

        withContext(Dispatchers.Main) {
            playerRepository.play(listOf(playable), startIndex = 0, startMs = adjustedPositionMs)
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
