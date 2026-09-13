package dev.nami.data

import android.util.Log
import dev.nami.domain.ServerLibraryRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private const val TAG = "ServerEvents"

/**
 * Постоянное подключение к `/api/ws` ради мгновенного применения чужих правок.
 *
 * Правки в другую сторону уже работали: переименование локального трека уходит на сервер сразу
 * (`LibraryRepositoryImpl` вызывает `updateMatchingTrack`), и сервер рассылает всем
 * `{"type":"changed","entities":["tracks"]}`. Слушать это было некому - сокет открывался только
 * для Jam-сессии, - поэтому на втором устройстве изменение появлялось лишь при следующем запуске
 * приложения. Здесь та же подписка, но постоянная, пока сервер подключён.
 *
 * Обновление не мгновенное по каждому событию, а с короткой паузой: правка альбома целиком - это
 * десятки событий подряд, и без склейки каждое тянуло бы за собой полный список треков с сервера.
 *
 * Чего тут намеренно нет: разбора того, ЧТО именно изменилось. Сервер присылает список
 * затронутых сущностей, но точечное применение потребовало бы отдельной ручки «отдай один трек
 * по id» и сверки версий, а полный список на обычной библиотеке - один запрос с пагинацией.
 * Появится ручка дельты - сузим здесь, пока переусложнять незачем.
 */
@Singleton
class ServerEventsRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val serverLibraryRepository: ServerLibraryRepository,
    private val syncRepository: SyncRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var closedByUs = false
    private var reconnectAttempt = 0
    private var refreshJob: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    init {
        // Токен и адрес вместе: смена любого из них - это другой сервер, старое соединение
        // держать нельзя.
        scope.launch {
            combine(settingsRepository.namiServerToken, settingsRepository.namiServerUrl) { token, url ->
                token.orEmpty() to url
            }
                .distinctUntilChanged()
                .collect { (token, url) ->
                    disconnect()
                    if (token.isNotBlank() && url.isNotBlank()) {
                        reconnectAttempt = 0
                        connect()
                    }
                }
        }
    }

    private fun disconnect() {
        closedByUs = true
        runCatching { socket?.close(1000, "server changed") }
        socket = null
        client = null
        _connected.value = false
    }

    private fun connect() {
        val token = settingsRepository.namiServerToken.value?.takeIf { it.isNotBlank() } ?: return
        val bases = settingsRepository.namiServerUrl.value
            .split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        val base = bases.firstOrNull() ?: return
        val cert = settingsRepository.namiServerCertSha256.value

        closedByUs = false
        val wsBase = base.replace(Regex("^http", RegexOption.IGNORE_CASE)) { m ->
            if (m.value == "HTTP") "WS" else "ws"
        }
        val okHttp = pinnedWebSocketClient(wsBase, cert)
        client = okHttp
        socket = okHttp.newWebSocket(
            Request.Builder().url("$wsBase/api/ws?token=$token").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    reconnectAttempt = 0
                    _connected.value = true
                    // Пока связи не было, что-то могло измениться - догоняем сразу.
                    scheduleRefresh(entities = setOf("tracks", "state"))
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handle(text)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "сокет отвалился: ${t.message}")
                    _connected.value = false
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (closedByUs) return
        val attempt = reconnectAttempt++
        // Три секунды, дальше вдвое, потолок минута: сервер дома может быть выключен часами,
        // и долбиться в него раз в три секунды всё это время незачем.
        val delayMs = min(60_000L, 3_000L * (1L shl min(attempt, 5)))
        scope.launch {
            delay(delayMs)
            if (!closedByUs && settingsRepository.namiServerToken.value?.isNotBlank() == true) connect()
        }
    }

    private fun handle(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (obj.optString("type") != "changed") return
        val entities = obj.optJSONArray("entities")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty().toSet()
        scheduleRefresh(entities)
    }

    private fun scheduleRefresh(entities: Set<String>) {
        // Склейка: правка альбома целиком приходит десятками событий подряд, и обновляться на
        // каждое - значит тянуть полный список треков десятки раз.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(DEBOUNCE_MS)
            val library = entities.isEmpty() ||
                entities.any { it == "tracks" || it == "albums" || it == "artists" || it.startsWith("artwork:") }
            if (library) runCatching { serverLibraryRepository.listTracks() }
            // "state" тут - собственная подстраховка при переподключении; сервер шлёт rating и
            // прочее пользовательское состояние, которое живёт в /api/sync, а не в списке треков.
            if (!library || entities.any { it == "rating" || it == "state" }) {
                runCatching { syncRepository.sync() }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
