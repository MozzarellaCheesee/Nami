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
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListenTogetherGuestState
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.WifiDirectPeer
import dev.nami.player.LocalHttpServer
import dev.nami.player.localIpAddress
import dev.nami.player.localIpAddresses
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
import kotlinx.coroutines.withContext
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
// Насколько гость может разойтись с хостом, прежде чем подтянуться перемоткой. 1500 мс было
// слишком щедро: расхождение почти в секунду - это уже отчётливо слышный "хор" на двух телефонах
// рядом, а исправлять его было нечем, потому что порог до него не доставал. Ниже ~250 мс смысла
// нет - там начинает доминировать джиттер сети, и гость дёргался бы перемоткой на ровном месте.
private const val DRIFT_THRESHOLD_MS = 350L
// Сколько подряд неудачных опросов /nowplaying терпим, прежде чем сказать гостю, что что-то не так.
// Один-два промаха - это нормальный джиттер сети или пауза у хоста, не повод пугать сообщением.
private const val FAILED_POLLS_BEFORE_ERROR = 3
// Wi-Fi Direct connect() иногда не доводит до WIFI_P2P_CONNECTION_CHANGED вообще (собеседник не
// принял приглашение, ушёл из зоны) - без таймаута экран навсегда застревал в "Подключение..."
// с заблокированными кнопками и без единого способа выйти, кроме перезапуска приложения.
private const val WIFI_DIRECT_CONNECT_TIMEOUT_MS = 40_000L

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

    private val _listenTogetherGuestCount = MutableStateFlow(0)
    override val listenTogetherGuestCount: StateFlow<Int> = _listenTogetherGuestCount
    private var guestCountJob: Job? = null

    private val _listenTogetherGuestState = MutableStateFlow<ListenTogetherGuestState?>(null)
    override val listenTogetherGuestState: StateFlow<ListenTogetherGuestState?> = _listenTogetherGuestState
    private val _listenTogetherError = MutableStateFlow<String?>(null)
    override val listenTogetherError: StateFlow<String?> = _listenTogetherError
    private var guestJob: Job? = null
    // Хост текущей гостевой сессии - нужен, чтобы "добавить в библиотеку" могло дотянуться за
    // тегами и обложкой к тому же устройству, откуда играет трек.
    private var guestHostDevice: DiscoveredDevice? = null
    private val listenTogetherCacheDir get() = File(context.cacheDir, "listen_together").apply { mkdirs() }
    private val cachedFilesByTrackId = mutableMapOf<String, File>()
    private val cachedArtworkByTrackId = mutableMapOf<String, String>()

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
    private var wifiDirectConnectTimeoutJob: Job? = null


    init {
        // Пока раздача (Wi-Fi Drop) запущена (dropTrack != null), она следует за играющим
        // треком автоматически - раньше setDropTrack() вызывался только один раз вручную и
        // раздача навсегда замирала на том треке, даже когда хост давно переключился на другой.
        scope.launch {
            playerRepository.queue.map { it.nowPlaying?.id }.distinctUntilChanged().collect { id ->
                if (_dropTrack.value == null) return@collect
                // В гостевой сессии играет чужой трек из кэша, которого в библиотеке нет - раздача
                // не должна за ним следовать и обнуляться.
                if (_listenTogetherGuestState.value != null) return@collect
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
            dropMetaJsonBlocking = { runBlocking { _dropTrack.value?.let { buildTrackMetaJson(it) } } },
            trackMetaJsonBlocking = { id -> runBlocking { trackById(id)?.let { buildTrackMetaJson(it) } } },
            trackCoverFileBlocking = { id -> runBlocking { trackById(id) }?.albumArtworkPath?.let { File(it) }?.takeIf { it.exists() } },
            artistPhotoFileBlocking = { id -> runBlocking { artistPhotoFile(trackById(id)) } },
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
        // Сервер живёт на голых сокетах и никого ни о чём не уведомляет - число слушающих
        // приходится опрашивать. Тем же тактом, что и гость опрашивает хоста.
        guestCountJob = scope.launch {
            while (true) {
                _listenTogetherGuestCount.value = server.guestCount()
                delay(POLL_INTERVAL_MS)
            }
        }
        registerNsd()
    }

    override fun stopServer() {
        httpServer?.stop()
        httpServer = null
        guestCountJob?.cancel()
        guestCountJob = null
        _listenTogetherGuestCount.value = 0
        _serverRunning.value = false
        _serverAddress.value = null
        unregisterNsd()
    }

    private fun buildNowPlayingJson(): JSONObject? {
        // Пока сами слушаем чужую сессию - не раздаём "что играет". Иначе, когда оба устройства
        // включают "показывать что играю" и подключаются друг к другу, получается петля: гость
        // играет чужой трек из кэша сессии (у него служебный id, в библиотеке гостя такого трека
        // нет), объявляет его своим nowplaying, собеседник видит смену трека, просит /track/<id> и
        // получает 404 - и так по кругу каждые POLL_INTERVAL_MS, обе стороны дёргаются впустую.
        if (_listenTogetherGuestState.value != null) return null
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
            // Гость сам себя тоже видит в этом числе (его запрос уже засчитан) - вычитает себя на
            // своей стороне, чтобы показать "и ещё N".
            put("guests", httpServer?.guestCount() ?: 0)
            put("upcoming", upcoming)
        }
    }

    /** Теги трека так, как их видит библиотека ОТДАЮЩЕГО. Сам аудиофайл может быть вообще без
     * тегов (тогда получатель видел бы UUID вместо названия и пустого артиста), а может быть с
     * устаревшими - в базе лежит то, что пользователь реально правил руками. Один формат и для
     * раздачи (/dropmeta), и для "слушать вместе" (/meta/<id>), чтобы получатель применял их
     * одним и тем же кодом. */
    private suspend fun buildTrackMetaJson(track: Track): JSONObject {
        val album = track.albumId?.let { runCatching { libraryRepository.album(it).first() }.getOrNull() }
        return JSONObject().apply {
            put("trackId", track.id.value)
            put("title", track.title)
            put("artistName", track.artistName ?: JSONObject.NULL)
            put("albumName", album?.title ?: JSONObject.NULL)
            put("year", album?.year ?: JSONObject.NULL)
            put("genre", track.genre ?: JSONObject.NULL)
            put("rating", track.rating ?: JSONObject.NULL)
            put("hasCover", track.albumArtworkPath?.let { File(it).exists() } == true)
            put("hasArtistPhoto", artistPhotoFile(track) != null)
        }
    }

    private suspend fun trackById(id: String): Track? =
        runCatching { libraryRepository.track(TrackId(id)).first() }.getOrNull()

    /** Фото артиста - отдельный файл от обложки альбома (см. /artistphoto/<id>). */
    private suspend fun artistPhotoFile(track: Track?): File? {
        val artistId = track?.artistId ?: return null
        val photo = runCatching { libraryRepository.artist(artistId).first() }.getOrNull()?.photoPath
        return photo?.let { File(it) }?.takeIf { it.exists() }
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
        // Адрес пересчитывается при каждом входе на экран: он считался один раз при старте сервера
        // и потом устаревал молча - после переключения Wi-Fi (или её включения обратно) экран и QR
        // показывали старый адрес, по которому уже никто не отвечает.
        if (_serverRunning.value) _serverAddress.value = "${localIpAddress()}:$SERVER_PORT"
        if (discoveryListener != null) return
        // Список НЕ чистится при старте поиска. Чистка ломала два сценария сразу: (1) подключённый
        // по Wi-Fi Direct peer добавляется в список вручную, а следом WIFI_P2P_CONNECTION_CHANGED
        // перезапускает автопоиск - и стирал устройство, которое сам же только что добавил, так что
        // Wi-Fi Direct не давал вообще ничего; (2) повторный заход на экран (ViewModel пересоздаётся,
        // init зовёт startDiscovery) терял и p2p-устройство, и уже найденное вручную. Устаревшие
        // записи убирает onServiceLost, а добавление и так дедуплицируется по host.
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
                            // without this, every device sees its own broadcast in the list. Сверяем
                            // со ВСЕМИ своими адресами, а не с одним "основным": в Wi-Fi Direct
                            // устройство анонсирует себя по p2p-адресу, и сравнение с адресом wlan0
                            // не совпадало - устройство показывало само себя как найденное.
                            if (host in localIpAddresses()) return
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

    // withContext(IO) не только ради сети (её httpDownload уже переключает сам), но и ради записи
    // скачанного трека на диск - вызов приходит из viewModelScope, то есть из main-потока.
    override suspend fun pullDrop(device: DiscoveredDevice): Boolean = withContext(Dispatchers.IO) {
        val (bytes, fileName) = httpDownload(device, "/drop") ?: return@withContext false
        // Уникальность даёт ПАПКА, а не префикс в имени файла: с "uuid_Fall Of Tears.mp3" импорт
        // подхватывал этот uuid как часть названия трека у тех файлов, где нет тегов.
        val scratchDir = File(File(context.cacheDir, "wifi_drop"), UUID.randomUUID().toString()).apply { mkdirs() }
        val scratchFile = File(scratchDir, fileName)
        scratchFile.writeBytes(bytes)
        return@withContext try {
            // Какой именно трек появился, импорт наружу не сообщает (отдаёт только прогресс) -
            // сравниваем состав библиотеки до и после. Файл ровно один, так что разница ровно одна.
            val before = libraryRepository.allTracksOrdered().map { it.id.value }.toSet()
            libraryRepository.import(ImportSource.Files(listOf(Uri.fromFile(scratchFile).toString()))).collect { }
            val imported = libraryRepository.allTracksOrdered().firstOrNull { it.id.value !in before }
            if (imported != null) applyRemoteMeta(device, httpGetJson(device, "/dropmeta"), imported.id)
            true
        } catch (e: Exception) {
            Log.w(TAG, "drop import failed", e)
            false
        } finally {
            scratchDir.deleteRecursively()
        }
    }

    /** Переносит теги, обложку и фото артиста из библиотеки отдающего на только что импортированный
     * трек - см. buildTrackMetaJson. Один и тот же путь и для Wi-Fi Drop, и для "добавить в
     * библиотеку" из "слушать вместе": добавленный трек не должен отличаться от переданного.
     * Молча пропускается, если метаданных нет (отдающий старой версии): трек всё равно уже в
     * библиотеке, ломать импорт из-за тегов незачем. */
    private suspend fun applyRemoteMeta(device: DiscoveredDevice, meta: JSONObject?, id: TrackId) {
        if (meta == null) return
        val remoteId = meta.optString("trackId").takeIf { it.isNotBlank() } ?: return
        meta.optString("title").takeIf { it.isNotBlank() }?.let { libraryRepository.renameTrack(id, it) }
        libraryRepository.batchEditTracks(
            ids = listOf(id),
            artistName = meta.optString("artistName", null)?.takeIf { it.isNotBlank() },
            albumName = meta.optString("albumName", null)?.takeIf { it.isNotBlank() },
            year = if (meta.isNull("year")) null else meta.optInt("year"),
            genre = meta.optString("genre", null)?.takeIf { it.isNotBlank() },
        )
        if (!meta.isNull("rating")) libraryRepository.setTrackRating(id, meta.optInt("rating"))

        // Альбом и артист берутся ПОСЛЕ batchEditTracks: он находит-или-создаёт их по имени через
        // тот же MetadataResolver, что и обычный импорт, поэтому трек подхватывает УЖЕ
        // СУЩЕСТВУЮЩИЙ у получателя альбом/артиста и дублей не появляется.
        val saved = libraryRepository.track(id).first()
        val album = saved?.albumId?.let { libraryRepository.album(it).first() }
        val artist = saved?.artistId?.let { libraryRepository.artist(it).first() }

        if (meta.optBoolean("hasCover")) {
            saveRemoteImage(device, "/cover/$remoteId", "cover_${id.value}")?.let { uri ->
                libraryRepository.setTrackCover(id, uri)
                // Обложка приезжает именно от АЛЬБОМА отдающего (Track.albumArtworkPath), поэтому
                // ставится и альбому - иначе трек с обложкой, а его альбом без. Но НЕ поверх уже
                // имеющейся: альбом мог быть у получателя давно и со своей обложкой, затирать её
                // чужой из-за одного добавленного трека нельзя.
                if (album != null && album.artworkPath.isNullOrBlank()) libraryRepository.setAlbumCover(album.id, uri)
            }
        }
        if (meta.optBoolean("hasArtistPhoto") && artist != null && artist.photoPath.isNullOrBlank()) {
            saveRemoteImage(device, "/artistphoto/$remoteId", "artist_${artist.id.value}")?.let { uri ->
                libraryRepository.setArtistPhoto(artist.id, uri)
            }
        }
    }

    /** Качает картинку с отдающего в постоянное хранилище (не в кэш - на неё будут ссылаться
     * альбом/артист, кэш система вправе вычистить в любой момент) и отдаёт file:// на неё. */
    private suspend fun saveRemoteImage(device: DiscoveredDevice, path: String, baseName: String): String? {
        val (bytes, name) = httpDownload(device, path) ?: return null
        val dir = File(context.filesDir, "shared_art").apply { mkdirs() }
        val file = File(dir, "$baseName.${File(name).extension.ifBlank { "jpg" }}")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    // ------------------------------------------------------------------ синхронизация

    override suspend fun syncWith(device: DiscoveredDevice): Int = withContext(Dispatchers.IO) {
        val json = httpGetJson(device, "/manifest") ?: return@withContext 0
        val remoteTracks = json.optJSONArray("tracks") ?: return@withContext 0
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
        return@withContext updated
    }

    // ------------------------------------------------------------------ слушать вместе

    override fun setListenTogetherHost(enabled: Boolean) {
        _listenTogetherHostEnabled.value = enabled
    }

    override fun joinListenTogether(device: DiscoveredDevice) {
        leaveListenTogether()
        _listenTogetherError.value = null
        guestHostDevice = device
        guestJob = scope.launch {
            // Раньше /info звался через runBlocking прямо из обработчика нажатия, то есть в main-
            // потоке - Android такой запрос не выполняет вообще (NetworkOnMainThreadException),
            // имя хоста всегда молча откатывалось на адрес устройства.
            val hostName = httpGetJson(device, "/info")?.optString("name")?.ifBlank { null } ?: device.name
            var failedPolls = 0
            while (true) {
                val requestStartedAt = android.os.SystemClock.elapsedRealtime()
                val json = httpGetJson(device, "/nowplaying")
                val oneWayLatencyMs = (android.os.SystemClock.elapsedRealtime() - requestStartedAt) / 2
                if (json == null) {
                    // Молчаливое ожидание навсегда было главной жалобой на "слушать вместе": хост
                    // недоступен или просто не включил "показывать что играю" (204) - гость видел
                    // ровно ничего и не знал, ждать ему или нет.
                    failedPolls++
                    if (failedPolls >= FAILED_POLLS_BEFORE_ERROR) {
                        _listenTogetherError.value =
                            if (_listenTogetherGuestState.value == null) {
                                "$hostName не делится тем, что играет - пусть включит \"показывать что играю\""
                            } else {
                                "Связь с $hostName потеряна"
                            }
                    }
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                failedPolls = 0
                _listenTogetherError.value = null
                val trackId = json.optString("trackId")
                val hostPositionMs = aheadByOneWay(json, oneWayLatencyMs)
                val current = _listenTogetherGuestState.value
                val otherGuests = (json.optInt("guests") - 1).coerceAtLeast(0)
                if (current?.trackId?.value != trackId) {
                    _listenTogetherGuestState.value = ListenTogetherGuestState(
                        hostName = hostName,
                        trackId = TrackId(trackId),
                        trackTitle = json.optString("title"),
                        artistName = json.optString("artistName", null),
                        downloading = cachedFilesByTrackId[trackId] == null,
                        cachedPath = cachedFilesByTrackId[trackId]?.path,
                        positionMs = hostPositionMs,
                        durationMs = json.optLong("durationMs"),
                        otherGuests = otherGuests,
                    )
                    val cached = cachedFilesByTrackId[trackId]
                    if (cached != null) {
                        playCached(cached, hostPositionMs, cachedArtworkFor(device, trackId))
                    } else {
                        downloadAndPlay(device, trackId, json.optString("title"), json.optString("artistName", null))
                    }
                } else {
                    _listenTogetherGuestState.value =
                        current.copy(positionMs = hostPositionMs, durationMs = json.optLong("durationMs"), otherGuests = otherGuests)
                    // Пока трек ещё качается, играет ПРЕДЫДУЩИЙ - подгонять его под позицию нового
                    // нечестно (перемотка в никуда), догоняем только когда играет то же, что у хоста.
                    if (!current.downloading) correctDrift(hostPositionMs, json.optBoolean("isPlaying", true))
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
        guestHostDevice = null
        _listenTogetherError.value = null
        val wasPlayingFromCache = _listenTogetherGuestState.value?.cachedPath != null
        _listenTogetherGuestState.value = null
        cachedFilesByTrackId.clear()
        cachedArtworkByTrackId.clear()
        // Плеер останавливается ДО удаления кэша сессии: раньше файл сносили из-под играющего
        // ExoPlayer, и вместо тишины оставался зависший плеер с чужим треком, которого уже нет.
        scope.launch {
            if (wasPlayingFromCache) withContext(Dispatchers.Main) { playerRepository.stop() }
            listenTogetherCacheDir.deleteRecursively()
        }
    }

    override suspend fun addCurrentListenTogetherTrackToLibrary(): Boolean = withContext(Dispatchers.IO) {
        val state = _listenTogetherGuestState.value ?: return@withContext false
        val path = state.cachedPath ?: return@withContext false
        val device = guestHostDevice
        val remoteTrackId = state.trackId?.value
        return@withContext try {
            // Ровно та же схема, что и Wi-Fi Drop: сначала файл, потом теги/обложка/фото артиста с
            // того же хоста. Раньше импортировался только аудиофайл, и добавленный из совместного
            // прослушивания трек оседал в библиотеке безымянным и без картинок, в отличие от того
            // же самого трека, полученного раздачей.
            val before = libraryRepository.allTracksOrdered().map { it.id.value }.toSet()
            libraryRepository.import(ImportSource.Files(listOf(Uri.fromFile(File(path)).toString()))).collect { }
            val imported = libraryRepository.allTracksOrdered().firstOrNull { it.id.value !in before }
            if (imported != null && device != null && remoteTrackId != null) {
                applyRemoteMeta(device, httpGetJson(device, "/meta/$remoteTrackId"), imported.id)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "listen-together import failed", e)
            false
        }
    }

    /** Позиция НЕ передаётся аргументом и берётся заново после скачивания: трек качается секунды
     * (а по слабому каналу и дольше), и позиция, снятая до скачивания, к моменту старта уже
     * устарела ровно на время загрузки - гость начинал играть заметно позади хоста и подтягивался
     * только следующей коррекцией дрейфа. */
    private suspend fun downloadAndPlay(device: DiscoveredDevice, trackId: String, title: String, artistName: String?) {
        val file = downloadOnly(device, trackId)
        if (file == null) {
            // Раньше просто return - гость навсегда оставался на "Скачивается..." без объяснения.
            if (_listenTogetherGuestState.value?.trackId?.value == trackId) {
                _listenTogetherError.value = "Не получилось скачать \"$title\" - трека нет у хоста или связь оборвалась"
                _listenTogetherGuestState.value = _listenTogetherGuestState.value?.copy(downloading = false)
            }
            return
        }
        // Only actually play it if the guest hasn't since moved on to a different track while
        // this download was in flight.
        if (_listenTogetherGuestState.value?.trackId?.value != trackId) return
        _listenTogetherGuestState.value = _listenTogetherGuestState.value?.copy(downloading = false, cachedPath = file.path)

        // Свежая позиция уже ПОСЛЕ скачивания - см. doc выше. Если хост за это время успел
        // переключиться ещё раз, стартовать этот трек незачем: следующий тик опроса разберётся.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val fresh = httpGetJson(device, "/nowplaying")
        val startPositionMs = when {
            fresh == null -> _listenTogetherGuestState.value?.positionMs ?: 0L
            fresh.optString("trackId") != trackId -> return
            else -> aheadByOneWay(fresh, (android.os.SystemClock.elapsedRealtime() - startedAt) / 2)
        }
        playCached(file, startPositionMs, cachedArtworkFor(device, trackId))
    }

    /** Позиция из ответа описывает момент, когда хост её измерил; пока ответ шёл к нам, хост
     * продолжал играть. Без этой поправки гость систематически отставал ровно на задержку сети, и
     * никакая коррекция дрейфа это не лечила - она сравнивала с той же устаревшей позицией. */
    private fun aheadByOneWay(json: JSONObject, oneWayLatencyMs: Long): Long {
        val position = json.optLong("positionMs")
        if (!json.optBoolean("isPlaying", true)) return position
        return (position + oneWayLatencyMs).coerceAtMost(json.optLong("durationMs").takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    private suspend fun downloadOnly(device: DiscoveredDevice, trackId: String): File? {
        if (cachedFilesByTrackId[trackId] != null) return cachedFilesByTrackId[trackId]
        val (bytes, fileName) = httpDownload(device, "/track/$trackId") ?: return null
        // Уникальность даёт папка, а не префикс в имени: имя файла идёт прямо в название трека при
        // "добавить в библиотеку", и "<uuid>_Артист - Трек.mp3" осело бы в библиотеке как есть,
        // если теги с хоста почему-то не доехали.
        val file = File(File(listenTogetherCacheDir, trackId).apply { mkdirs() }, fileName)
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
    /** [hostPositionMs] должен быть уже приведён к "сейчас" через [aheadByOneWay] - позиция в
     * ответе описывает момент, когда хост её измерил, а не момент, когда мы её прочитали. */
    private suspend fun correctDrift(hostPositionMs: Long, hostIsPlaying: Boolean) = withContext(Dispatchers.Main) {
        val playing = playerRepository.state.value as? PlaybackState.Playing ?: return@withContext
        if (playing.isPlaying != hostIsPlaying) {
            playerRepository.toggle()
        }
        val drift = playing.positionMs - hostPositionMs
        if (kotlin.math.abs(drift) > DRIFT_THRESHOLD_MS) {
            playerRepository.seek(hostPositionMs)
        }
    }

    // Все обращения к плееру - строго с main-потока: под капотом media3 MediaController, он
    // проверяет поток и падает с IllegalStateException ("called from a wrong thread"). Гостевой
    // цикл живёт на Dispatchers.IO, поэтому КАЖДЫЙ вход в "слушать вместе" ронял приложение
    // насмерть в момент старта воспроизведения.
    private suspend fun playCached(file: File, startPositionMs: Long, artworkPath: String?) = withContext(Dispatchers.Main) {
        playerRepository.play(
            listOf(
                dev.nami.domain.PlayableTrack(
                    id = TrackId(file.nameWithoutExtension),
                    title = _listenTogetherGuestState.value?.trackTitle ?: file.nameWithoutExtension,
                    artistName = _listenTogetherGuestState.value?.artistName,
                    path = file.path,
                    artworkPath = artworkPath,
                ),
            ),
            startIndex = 0,
            startMs = startPositionMs,
        )
    }

    /** Обложка чужого трека в кэш сессии - без неё гость слушал вместе с хостом, но смотрел на
     * пустой серый квадрат вместо картинки, хотя у хоста она есть. Живёт там же, где сам трек, и
     * удаляется вместе с сессией. */
    private suspend fun cachedArtworkFor(device: DiscoveredDevice, trackId: String): String? {
        cachedArtworkByTrackId[trackId]?.let { return it }
        val (bytes, name) = httpDownload(device, "/cover/$trackId") ?: return null
        val file = File(File(listenTogetherCacheDir, trackId).apply { mkdirs() }, "cover.${File(name).extension.ifBlank { "jpg" }}")
        file.writeBytes(bytes)
        return file.path.also { cachedArtworkByTrackId[trackId] = it }
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
                                mergeWifiDirectPeers(
                                    peers.deviceList.map {
                                        WifiDirectPeer(name = it.deviceName, address = it.deviceAddress, status = peerStatusLabel(it.status))
                                    },
                                )
                            }
                            refreshWifiDirectGroup()
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                            if (networkInfo?.isConnected == true) {
                                manager.requestConnectionInfo(channel) { info ->
                                    _wifiDirectConnecting.value = false
                                    wifiDirectConnectTimeoutJob?.cancel()
                                    // Группа поднимает новый интерфейс (p2p-*) со своим адресом, а
                                    // серверный адрес считался один раз при старте сервера и
                                    // оставался старым - QR и "видно как ..." показывали адрес
                                    // обычной Wi-Fi, недостижимый для собеседника по Wi-Fi Direct.
                                    if (_serverRunning.value) _serverAddress.value = "${localIpAddress()}:$SERVER_PORT"
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
                                    // Кто именно оказался в группе - см. refreshWifiDirectGroup:
                                    // владелец узнаёт своих клиентов только отсюда.
                                    refreshWifiDirectGroup()
                                }
                            } else {
                                // Группа распалась (вышли/переключились/собеседник ушёл) - раньше
                                // устройство так и оставалось в списке навсегда, будто всё ещё
                                // подключено.
                                wifiDirectConnectedHost?.let { host ->
                                    _discoveredDevices.value = _discoveredDevices.value.filterNot { it.host == host }
                                }
                                wifiDirectConnectedHost = null
                                wifiDirectConnectTimeoutJob?.cancel()
                                _wifiDirectConnecting.value = false
                                _wifiDirectConnected.value = false
                                // Снять пометку "сопряжено" со всех - группы больше нет.
                                applyWifiDirectGroupMembers(emptyList())
                                if (_serverRunning.value) _serverAddress.value = "${localIpAddress()}:$SERVER_PORT"
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
        // Сразу, ещё до результатов поиска: группа могла остаться поднятой с прошлого раза (она
        // живёт на уровне ОС и сама не рвётся), и собеседник должен быть виден как сопряжённый
        // немедленно при входе на экран, а не только после того, как поиск случайно его найдёт.
        refreshWifiDirectGroup()
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
        // Приёмник СПЕЦИАЛЬНО остаётся зарегистрированным (репозиторий Singleton, приёмник висит на
        // application-контексте и ничего не течёт). Раньше уход с экрана его снимал, и пока
        // Wi-Fi Direct группа оставалась поднятой на уровне ОС, приложение переставало слышать её
        // разрыв: wifiDirectConnected навсегда застревал в true, кнопка "Отключить" оставалась
        // висеть на уже несуществующей группе, а подключившийся собеседник не появлялся в списке.
        wifiP2pChannel?.let { channel -> runCatching { wifiP2pManager?.stopPeerDiscovery(channel, null) } }
        // Список СПЕЦИАЛЬНО не чистится: сопряжённое устройство надо показать сразу при следующем
        // входе на экран, а не ждать, пока поиск найдёт его заново (он может и не найти - в группе
        // устройства друг друга в обычном поиске уже не видят).
        _wifiDirectPeers.value = _wifiDirectPeers.value.filter { it.connected }
    }

    override fun connectWifiDirect(peer: WifiDirectPeer) {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        pendingWifiDirectPeerName = peer.name
        _wifiDirectConnecting.value = true
        // Приглашение может просто остаться без ответа (собеседник его не увидел/отклонил) - тогда
        // WIFI_P2P_CONNECTION_CHANGED не приходит ВООБЩЕ, и без таймаута "Подключение..." висит
        // вечно, а все кнопки "Подключить" остаются заблокированными.
        wifiDirectConnectTimeoutJob?.cancel()
        wifiDirectConnectTimeoutJob = scope.launch {
            delay(WIFI_DIRECT_CONNECT_TIMEOUT_MS)
            if (!_wifiDirectConnected.value) _wifiDirectConnecting.value = false
        }
        val config = WifiP2pConfig().apply { deviceAddress = peer.address }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    wifiDirectConnectTimeoutJob?.cancel()
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
        wifiDirectConnectTimeoutJob?.cancel()
        applyWifiDirectGroupMembers(emptyList())
    }

    /** Состав текущей Wi-Fi Direct группы - единственный симметричный источник правды о том, с кем
     * мы реально сопряжены: владелец видит здесь своих клиентов, клиент - владельца. Результаты
     * обычного поиска (requestPeers) для этого не годятся - после переоткрытия экрана поиск
     * начинается заново, и уже сопряжённое устройство в нём может не появиться вообще, из-за чего
     * экран показывал "никого не видно рядом" поверх живого соединения. */
    private fun refreshWifiDirectGroup() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        try {
            manager.requestGroupInfo(channel) { group ->
                val members = when {
                    group == null -> emptyList()
                    group.isGroupOwner -> group.clientList.orEmpty().toList()
                    else -> listOfNotNull(group.owner)
                }
                _wifiDirectConnected.value = group != null
                if (group != null) {
                    _wifiDirectConnecting.value = false
                    wifiDirectConnectTimeoutJob?.cancel()
                }
                applyWifiDirectGroupMembers(
                    members.map {
                        WifiDirectPeer(
                            name = it.deviceName?.takeIf { n -> n.isNotBlank() } ?: "Wi-Fi Direct",
                            address = it.deviceAddress,
                            status = "сопряжено",
                            connected = true,
                        )
                    },
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi Direct requestGroupInfo denied: ${e.message}")
        }
    }

    /** Состав группы - истина в последней инстанции по признаку "сопряжено": кого в нём нет, тот
     * сопряжённым больше не считается (иначе после разрыва пометка висела бы вечно). Устройства,
     * известные только по составу группы, добавляются в список - после переоткрытия экрана поиск
     * ещё пуст, а показать сопряжённого собеседника надо сразу. */
    private fun applyWifiDirectGroupMembers(members: List<WifiDirectPeer>) {
        val memberAddresses = members.map { it.address }.toSet()
        val updated = _wifiDirectPeers.value.map { peer ->
            when {
                peer.address in memberAddresses -> peer.copy(connected = true, status = "сопряжено")
                peer.connected -> peer.copy(connected = false, status = "доступно")
                else -> peer
            }
        }
        val unknown = members.filter { m -> updated.none { it.address == m.address } }
        _wifiDirectPeers.value = (updated + unknown).sortedByDescending { it.connected }
    }

    /** Результаты поиска рядом дополняют список, не затирая признак "сопряжено": поиск про группу
     * ничего не знает и, обновляя запись, сбросил бы пометку с уже подключённого устройства. */
    private fun mergeWifiDirectPeers(incoming: List<WifiDirectPeer>) {
        val byAddress = LinkedHashMap<String, WifiDirectPeer>()
        _wifiDirectPeers.value.forEach { byAddress[it.address] = it }
        incoming.forEach { peer ->
            val existing = byAddress[peer.address]
            byAddress[peer.address] = if (existing?.connected == true) existing.copy(name = peer.name) else peer
        }
        _wifiDirectPeers.value = byAddress.values.sortedByDescending { it.connected }
    }

    private fun peerStatusLabel(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "подключено"
        WifiP2pDevice.INVITED -> "приглашение отправлено"
        WifiP2pDevice.FAILED -> "ошибка"
        WifiP2pDevice.AVAILABLE -> "доступно"
        WifiP2pDevice.UNAVAILABLE -> "недоступно"
        else -> "неизвестно"
    }

    // ------------------------------------------------------------------ HTTP client helpers

    // withContext(IO) стоит ЗДЕСЬ, а не у каждого вызывающего: pullDrop и syncWith вызываются из
    // viewModelScope (Dispatchers.Main), сами диспетчер не переключали, и каждый их запрос падал с
    // NetworkOnMainThreadException - "Получить раздачу" и "Синхр." не работали вообще никогда,
    // молча показывая "Не получилось скачать" / "Синхронизировано треков: 0". Одна точка входа
    // закрывает и уже существующие вызовы, и любые будущие.
    private suspend fun httpGetJson(device: DiscoveredDevice, path: String): JSONObject? = withContext(Dispatchers.IO) {
        val conn = openConnection(device, path, readTimeoutMs = 5000)
        try {
            if (conn == null || conn.responseCode !in 200..299) return@withContext null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun httpDownload(device: DiscoveredDevice, path: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val conn = openConnection(device, path, readTimeoutMs = 60_000)
        try {
            if (conn == null || conn.responseCode !in 200..299) return@withContext null
            // Имя приходит процентно-кодированным (заголовки HTTP - ISO-8859-1, а названия треков
            // сплошь и рядом не ASCII) - см. encodeFilenameHeader на стороне сервера.
            val fileName = conn.getHeaderField("X-Original-Filename")
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "track.audio"
            conn.inputStream.use { it.readBytes() } to fileName
        } catch (e: Exception) {
            // Полный exception, а не только message: у половины сетевых ошибок message == null, и в
            // логе оставалось бесполезное "download /drop failed: null" без единой зацепки.
            Log.w(TAG, "download $path failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    // disconnect() в finally - соединения раньше не закрывались вообще, а keep-alive пул держит
    // сокет открытым: при опросе "слушать вместе" раз в POLL_INTERVAL_MS они копились всю сессию.
    private fun openConnection(device: DiscoveredDevice, path: String, readTimeoutMs: Int): HttpURLConnection? = try {
        (URL("http://${device.host}:${device.port}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = readTimeoutMs
        }
    } catch (e: Exception) {
        Log.w(TAG, "connect $path failed", e)
        null
    }
}
