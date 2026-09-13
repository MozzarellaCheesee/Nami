package dev.nami.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.data.mapper.toDomain
import dev.nami.domain.ServerLibraryRepository
import dev.nami.domain.ServerTrackMeta
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerLibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val metadataResolver: MetadataResolver,
    private val searchRepository: SearchRepository,
) : ServerLibraryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Сколько файлов выгружается одновременно. Четыре перекрывают ожидание сети и обработку на
     * сервере, не забивая канал домашнего сервера полностью. */
    private val UPLOAD_CONCURRENCY = 4
    private val uploadMutex = Mutex()

    /** Обложки, которые качаются прямо сейчас. listTracks() вызывается по каждому событию
     * "changed" и на каждом вызове запускал новую корутину скачивания для того же id, пока
     * предыдущая не закончила: две записи в один файл портили его. Ключ убирается в finally,
     * поэтому неудачная попытка не блокирует повтор. */
    private val artworkInFlight = ConcurrentHashMap.newKeySet<Long>()
    private val _uploadProgress = MutableStateFlow<String?>(null)
    private val cachedAudioIds = ConcurrentHashMap.newKeySet<Long>()
    private val cachedArtworkIds = ConcurrentHashMap.newKeySet<Long>()
    override val uploadProgress: StateFlow<String?> = _uploadProgress

    init {
        // Появился токен - сразу тянем список треков. Раньше это делал только LibraryViewModel
        // в своём init, поэтому после входа в аккаунт зеркала серверной библиотеки не появлялись
        // до перезапуска приложения: ViewModel уже была создана, когда сервера ещё не было.
        scope.launch {
            settingsRepository.namiServerToken
                .map { !it.isNullOrBlank() }
                .distinctUntilChanged()
                .collect { hasToken -> if (hasToken) runCatching { listTracks() } }
        }
        scope.launch {
            cacheDir.listFiles()?.forEach { file ->
                val id = file.nameWithoutExtension.toLongOrNull() ?: return@forEach
                if (file.length() <= 0L) return@forEach
                if (file.name.endsWith(".audio")) cachedAudioIds += id
                if (file.name.endsWith(".artwork")) cachedArtworkIds += id
            }
        }
    }

    override fun clearUploadProgress() {
        _uploadProgress.value = null
    }

    override suspend fun clearMirroredTracks() {
        if (trackDao.deleteAllServerTracks() > 0) searchRepository.rebuildIndex()
    }

    override fun uploadTracksBackground(allTracks: List<Track>) {
        if (allTracks.isEmpty()) return
        scope.launch {
            uploadMutex.withLock {
            val cfg = activeConfig()
            if (cfg == null) {
                _uploadProgress.value = "Сервер не подключён"
                return@withLock
            }

            val (tracks, withoutMirrorsSize, missingFiles) = uploadCandidates(allTracks)
            if (tracks.isEmpty()) {
                _uploadProgress.value = if (missingFiles > 0) {
                    "Нечего отправлять: файлов не найдено на диске - $missingFiles"
                } else {
                    "Все треки ($withoutMirrorsSize) уже есть на сервере"
                }
                return@withLock
            }

            val total = tracks.size
            _uploadProgress.value = "Проверка наличия на сервере: 0/$total…"

            val matchRequests = tracks.map {
                NamiServerClient.MatchTrackRequest(
                    title = it.title,
                    artist = it.artistName,
                    durationMs = it.durationMs,
                    fileHash = it.fileHash,
                )
            }

            val matchResults = NamiServerClient.matchTracks(cfg, matchRequests) { done, _ ->
                _uploadProgress.value = "Проверка наличия на сервере: $done/$total…"
            }

            val toUpload = mutableListOf<Track>()
            var alreadyOnServer = 0

            if (matchResults != null && matchResults.size == tracks.size) {
                for (i in tracks.indices) {
                    if (matchResults[i] != null) {
                        alreadyOnServer++
                    } else {
                        toUpload.add(tracks[i])
                    }
                }
            } else {
                toUpload.addAll(tracks)
            }

            if (toUpload.isEmpty()) {
                _uploadProgress.value = "Все треки ($total) уже есть на сервере"
                return@withLock
            }

            val needUploadCount = toUpload.size
            var uploaded = 0
            var duplicates = 0
            var failed = 0

            _uploadProgress.value = if (alreadyOnServer > 0) {
                "На сервере уже $alreadyOnServer из $total. Отправка новых: 0/$needUploadCount…"
            } else {
                "Отправка на сервер: 0/$needUploadCount…"
            }

            // Отправка шла строго по одному файлу, и на каждом треке простаивала то сеть, то
            // сервер (он читает теги и считает хеш уже принятого файла). Несколько потоков
            // перекрывают эти ожидания.
            // ponytail: фиксированные UPLOAD_CONCURRENCY потоков, без подстройки под скорость
            // канала - если понадобится, здесь и менять.
            val queue = java.util.concurrent.ConcurrentLinkedQueue(toUpload)
            val done = java.util.concurrent.atomic.AtomicInteger()
            val uploadedCount = java.util.concurrent.atomic.AtomicInteger()
            val duplicateCount = java.util.concurrent.atomic.AtomicInteger()
            val failedCount = java.util.concurrent.atomic.AtomicInteger()

            coroutineScope {
                repeat(minOf(UPLOAD_CONCURRENCY, needUploadCount)) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val track = queue.poll() ?: break
                            val res = uploadLocalTrack(track.path)
                            when {
                                res == "Уже есть на сервере" -> duplicateCount.incrementAndGet()
                                res != null -> uploadedCount.incrementAndGet()
                                else -> failedCount.incrementAndGet()
                            }
                            val finished = done.incrementAndGet()
                            _uploadProgress.value = if (alreadyOnServer > 0) {
                                "На сервере уже $alreadyOnServer из $total. Отправка новых: $finished/$needUploadCount…"
                            } else {
                                "Отправка на сервер: $finished/$needUploadCount…"
                            }
                        }
                    }
                }
            }

            uploaded = uploadedCount.get()
            duplicates = duplicateCount.get()
            failed = failedCount.get()

            _uploadProgress.value = buildString {
                append("Выгрузка завершена: ")
                if (uploaded > 0) append("загружено $uploaded ")
                val totalAlready = alreadyOnServer + duplicates
                if (totalAlready > 0) append("(уже было на сервере: $totalAlready) ")
                if (failed > 0) append("ошибок $failed ")
                if (missingFiles > 0) append("файлов не найдено на диске: $missingFiles")
            }.trim()
            }
        }
    }

    override fun uploadPathsBackground(paths: List<String>) {
        if (paths.isEmpty()) return
        scope.launch {
            val dbMap = runCatching {
                trackDao.allOrderedWithArtwork().associateBy { it.track.path }
            }.getOrNull()

            val tracks = paths.map { path ->
                val item = dbMap?.get(path)
                if (item != null) {
                    Track(
                        id = TrackId(item.track.id),
                        title = item.track.title,
                        artistId = item.track.artistId?.let { ArtistId(it) },
                        albumId = item.track.albumId?.let { AlbumId(it) },
                        durationMs = item.track.durationMs,
                        path = item.track.path,
                        format = item.track.format,
                        sizeBytes = item.track.sizeBytes,
                        dateAdded = item.track.dateAdded,
                        artistName = item.artistName,
                        fileHash = item.track.fileHash,
                    )
                } else {
                    val f = File(path)
                    Track(
                        id = TrackId(path),
                        title = f.nameWithoutExtension,
                        artistId = null,
                        albumId = null,
                        durationMs = 0L,
                        path = path,
                        format = f.extension,
                        sizeBytes = f.length(),
                        dateAdded = System.currentTimeMillis(),
                    )
                }
            }
            uploadTracksBackground(tracks)
        }
    }

    /** Офлайн-кеш серверных треков - в приватном хранилище приложения. */
    private val cacheRoot: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "ServerCache").apply { mkdirs() }
    }
    @Volatile private var cachedScopedDir: Pair<String, File>? = null
    private val cacheDir: File
        get() {
            val identity = listOf(
                settingsRepository.namiServerToken.value.orEmpty(),
                settingsRepository.namiServerCertSha256.value.orEmpty(),
                settingsRepository.namiServerUrl.value.split('\n', ',').map { it.trim() }.sorted().joinToString("|"),
            ).joinToString("\n")
            cachedScopedDir?.takeIf { it.first == identity }?.let { return it.second }
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            val scoped = File(cacheRoot, key).apply { mkdirs() }
            // Старые версии держали один общий кеш. Однократно переносим его текущему аккаунту.
            cacheRoot.listFiles { file -> file.isFile && (file.name.endsWith(".audio") || file.name.endsWith(".artwork")) }
                ?.forEach { legacy -> legacy.renameTo(File(scoped, legacy.name)) }
            cachedScopedDir = identity to scoped
            return scoped
        }

    override fun isServerActive(): Boolean =
        !settingsRepository.namiServerToken.value.isNullOrBlank() &&
            settingsRepository.namiServerUrl.value.isNotBlank()

    private fun activeConfig(): NamiServerClient.Config? {
        if (!isServerActive()) return null
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        val bases = settingsRepository.namiServerUrl.value
            .split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (bases.isEmpty()) return null
        return NamiServerClient.Config(bases.first(), token, cert, bases)
    }

    override suspend fun listTracks(limit: Int, offset: Int): List<ServerTrackMeta>? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val startedAt = System.currentTimeMillis()
            val pageSize = limit.coerceIn(1, 1000)
            val tracks = mutableListOf<ServerTrackMeta>()
            var nextOffset = offset.coerceAtLeast(0)
            var pageCount: Int
            do {
                val arr = NamiServerClient.tracks(cfg, pageSize, nextOffset) ?: return@withContext null
                pageCount = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    tracks += parseTrackMeta(o) ?: continue
                }
                nextOffset += pageCount
            } while (pageCount == pageSize)
            mirrorIntoLibrary(tracks)
            // Полный список только что привёл устройство в соответствие с сервером - дальше
            // хватит дельты. Отметку берём ДО запроса: изменение, случившееся во время
            // выкачивания, иначе потерялось бы навсегда.
            deltaCursor = startedAt
            tracks
        }

    /** Серверные записи живут в общей Room-библиотеке, поэтому все существующие очереди,
     * shuffle, Home и поиск получают их без специальных веток в UI. */
    private suspend fun mirrorIntoLibrary(serverTracks: List<ServerTrackMeta>) {
        val incomingById = serverTracks.associateBy { "server_${it.id}" }
        val mirrored = trackDao.allRaw().filter { it.id.startsWith("server_") }
        mirrored.groupBy { it.albumId }.filterKeys { it != null }.forEach { (albumId, tracks) ->
            val incoming = tracks.mapNotNull { incomingById[it.id] }
            val titles = incoming.mapNotNull { it.album }.distinct()
            if (incoming.size == tracks.size && titles.size == 1) {
                albumDao.updateTitle(albumId!!, titles.single())
                val years = incoming.map { it.year }.distinct()
                if (years.size == 1) albumDao.setYear(albumId, years.single())
            }
        }
        mirrored.groupBy { it.artistId }.filterKeys { it != null }.forEach { (artistId, tracks) ->
            val names = tracks.mapNotNull { incomingById[it.id]?.artist?.takeIf(String::isNotBlank) }.distinct()
            if (names.size == 1) artistDao.updateName(artistId!!, names.single())
        }
        val localTracks = trackDao.allOrderedWithArtwork()
            .map { it.toDomain() }
            .filterNot { it.path.startsWith(SERVER_PATH_PREFIX) }
        // Одно правило дедупа на всю библиотеку (см. DedupKey): название, артист, альбом. Раньше
        // здесь было своё условие с допуском по длительности и вовсе без альбома, из-за чего один
        // и тот же трек считался дублем по одним правилам при импорте и по другим при зеркалении.
        // Название альбома берётся разом, а не запросом на каждый трек: список локальных треков -
        // это вся библиотека, и на паре тысяч записей это была бы пара тысяч запросов.
        val albumTitles = albumDao.allForIndexing().associate { it.id to it.title }
        val pairs = pairServerTracksWithLocal(
            serverTracks = serverTracks,
            localTracks = localTracks,
            albumTitleOf = { albumTitles[it] },
            primaryArtist = { metadataResolver.primaryArtistName(it) },
        )
        val unique = mutableListOf<ServerTrackMeta>()
        for ((server, local) in pairs) {
            if (local == null) {
                unique += server
                continue
            }
            if (local.serverTrackId != server.id) {
                trackDao.setServerTrackId(local.id.value, server.id)
            }
            applyServerMetadata(local, server, albumTitles)
        }
        val ids = unique.map { "server_${it.id}" }
        if (ids.isEmpty()) trackDao.deleteAllServerTracks() else trackDao.deleteServerTracksExcept(ids)

        unique.forEach { track ->
            val artistId = metadataResolver.resolveArtist(track.artist)
            val albumId = metadataResolver.resolveAlbum(track.album, artistId, track.year)
            val id = "server_${track.id}"
            val artworkPath = cachedArtwork(track.id)?.absolutePath
            trackDao.insertAll(
                listOf(
                    TrackEntity(
                        id = id,
                        title = track.title,
                        artistId = artistId,
                        albumId = albumId,
                        trackNo = track.trackNo,
                        discNo = null,
                        durationMs = track.durationMs,
                        path = "$SERVER_PATH_PREFIX${track.id}",
                        format = track.format ?: "server",
                        sizeBytes = track.sizeBytes,
                        dateAdded = System.currentTimeMillis(),
                        lastPlayed = null,
                        playCount = 0,
                        artworkPath = artworkPath,
                    ),
                ),
            )
            trackDao.updateServerTrack(
                id, track.title, artistId, albumId, track.trackNo, track.durationMs,
                track.format ?: "server", track.sizeBytes, artworkPath,
            )
            if (artworkPath != null) {
                albumId?.let { albumDao.setArtworkPath(it, artworkPath) }
            } else {
                scope.launch {
                    downloadArtwork(track.id)?.let { artwork ->
                        trackDao.setArtworkPath(id, artwork.absolutePath)
                        albumId?.let { albumDao.setArtworkPath(it, artwork.absolutePath) }
                    }
                }
            }
        }
        searchRepository.rebuildIndex()
    }

    /** Разбор одной строки трека из ответа сервера. Общий для списка и для дельты: два разбора
     * одного и того же JSON разошлись бы при первом же новом поле. */
    private fun parseTrackMeta(o: org.json.JSONObject): ServerTrackMeta? {
        val id = o.optLong("id", -1)
        if (id < 0) return null
        return ServerTrackMeta(
            id = id,
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album").takeIf { it.isNotBlank() },
            durationMs = o.optLong("duration_ms", 0L),
            trackNo = o.optInt("track_no").takeIf { o.has("track_no") && !o.isNull("track_no") },
            year = o.optInt("year").takeIf { o.has("year") && !o.isNull("year") },
            sizeBytes = o.optLong("size_bytes", 0L),
            format = o.optString("format").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** Курсор дельты: момент сервера, до которого изменения уже применены. Ноль - ещё ничего
     * не забирали, значит первый раз нужен полный список. Хранится в настройках, а не в памяти:
     * иначе каждый запуск приложения начинался бы с полной перекачки. */
    private var deltaCursor: Long
        get() = settingsRepository.serverDeltaCursor.value
        set(value) = settingsRepository.setServerDeltaCursor(value)

    override suspend fun applyServerChanges(): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        val since = deltaCursor
        if (since <= 0L) return@withContext false

        val obj = NamiServerClient.tracksDelta(cfg, since) ?: return@withContext false
        val changed = obj.optJSONArray("changed")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseTrackMeta) }
        }.orEmpty()
        val deleted = obj.optJSONArray("deleted")?.let { arr ->
            (0 until arr.length()).map { arr.optLong(it) }
        }.orEmpty()
        // Если влезло не всё - курсор не двигаем и уходим на полный список: догонять дельту
        // порциями сложнее, чем один раз забрать список, а случай этот редкий.
        if (obj.optBoolean("truncated", false)) return@withContext false

        for (id in deleted) {
            trackDao.hardDelete("server_$id")
            trackDao.findByServerTrackId(id)?.let { trackDao.setServerTrackId(it.id, null) }
            invalidateArtwork(id)
        }

        val albumTitles = albumDao.allForIndexing().associate { it.id to it.title }
        for (server in changed) {
            val mirror = trackDao.findById("server_${server.id}")
            val linked = trackDao.findByServerTrackId(server.id)
            // Трека нет ни зеркалом, ни связанным локальным файлом: он новый для этого
            // устройства, и провести его надо обычным зеркалированием - там дедуп, обложки и
            // разрешение артиста с альбомом.
            if (mirror == null && linked == null) return@withContext false

            if (mirror != null) {
                val artistId = metadataResolver.resolveArtist(server.artist)
                val albumId = metadataResolver.resolveAlbum(server.album, artistId, server.year)
                trackDao.updateServerTrack(
                    mirror.id, server.title, artistId, albumId, server.trackNo, server.durationMs,
                    server.format ?: "server", server.sizeBytes, mirror.artworkPath,
                )
            }
            linked?.let { applyServerMetadata(it.toDomain(), server, albumTitles) }
        }

        if (changed.isNotEmpty() || deleted.isNotEmpty()) searchRepository.rebuildIndex()
        deltaCursor = obj.optLong("now", since)
        true
    }

    /** Чужая правка, доехавшая до локального файла. Сервер - источник правды по метаданным:
     * своя правка уходит на него сразу (см. LibraryRepositoryImpl.updateMatchingTrack), поэтому
     * расхождение означает, что кто-то поменял трек на другом устройстве.
     *
     * Путь, формат и размер не трогаем: файл на устройстве свой, сервер про него ничего не знает. */
    private suspend fun applyServerMetadata(
        local: Track,
        server: ServerTrackMeta,
        albumTitles: Map<String, String>,
    ) {
        val sameTitle = local.title == server.title
        val sameArtist = local.artistName.orEmpty() == server.artist
        val sameAlbum = local.albumId?.value?.let { albumTitles[it] }.orEmpty() == server.album.orEmpty()
        if (sameTitle && sameArtist && sameAlbum) return

        val artistId = metadataResolver.resolveArtist(server.artist)
        val albumId = metadataResolver.resolveAlbum(server.album, artistId, server.year)
        trackDao.updateMetadataFromServer(local.id.value, server.title, artistId, albumId, server.trackNo)
    }

    override suspend fun downloadTrack(serverTrackId: Long): File? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val dest = fileFor(serverTrackId)
            val artDest = artworkFileFor(serverTrackId)
            if (dest.exists() && dest.length() > 0) {
                cachedAudioIds += serverTrackId
                if (!artDest.exists() || artDest.length() == 0L) {
                    // Через downloadArtwork(), а не напрямую через клиента: там стоит
                    // защита от параллельной записи в один и тот же файл.
                    downloadArtwork(serverTrackId)
                }
                localizeMirror(serverTrackId, dest)
                return@withContext dest
            }
            val tmp = File(dest.parentFile, "${dest.name}.part")
            val ok = NamiServerClient.downloadTrack(cfg, serverTrackId, tmp)
            if (!ok || tmp.length() == 0L) {
                tmp.delete()
                return@withContext null
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return@withContext null
            }
            cachedAudioIds += serverTrackId
            // Параллельно подтягиваем и сохраняем обложку трека для офлайн-режима
            if (!artDest.exists() || artDest.length() == 0L) {
                // Через downloadArtwork(), а не напрямую через клиента: там стоит
                // защита от параллельной записи в один и тот же файл.
                downloadArtwork(serverTrackId)
            }
            localizeMirror(serverTrackId, dest)
            dest
        }

    /** Трек остаётся и на сервере, но в приложении становится полностью локальным: тот же
     * приём, что и в deleteFromServerOnly - меняем path у существующей строки-зеркала на
     * реальный файл, id не трогаем (на него ссылаются плейлисты и история). Смены пути
     * достаточно: зеркала чистятся синхронизацией по `path LIKE 'nami-server://%'`, так что
     * строка с реальным путём её переживёт. mirrorIntoLibrary дедуплицирует по (название,
     * артист, длительность), так что повторного зеркала для этого трека не появится. */
    private suspend fun localizeMirror(serverTrackId: Long, dest: File) {
        trackDao.setPath("server_$serverTrackId", dest.absolutePath)
        searchRepository.rebuildIndex()
    }

    override fun cachedFile(serverTrackId: Long): File? =
        fileFor(serverTrackId).takeIf { serverTrackId in cachedAudioIds }

    override fun cachedArtwork(serverTrackId: Long): File? =
        artworkFileFor(serverTrackId).takeIf { serverTrackId in cachedArtworkIds }

    override suspend fun downloadArtwork(serverTrackId: Long): File? = withContext(Dispatchers.IO) {
        // Уже качается этим же процессом - второй заход писал бы в тот же файл параллельно.
        if (!artworkInFlight.add(serverTrackId)) return@withContext null
        try {
            downloadArtworkLocked(serverTrackId)
        } finally {
            artworkInFlight -= serverTrackId
        }
    }

    private suspend fun downloadArtworkLocked(serverTrackId: Long): File? = withContext(Dispatchers.IO) {
        cachedArtwork(serverTrackId)?.let { return@withContext it }
        val cfg = activeConfig() ?: return@withContext null
        val dest = artworkFileFor(serverTrackId)
        if (NamiServerClient.downloadArtwork(cfg, serverTrackId, dest)) {
            cachedArtworkIds += serverTrackId
            dest
        } else null
    }

    override fun cachedTrackIds(): Set<Long> = cachedAudioIds.toSet()

    override fun removeFromCache(serverTrackId: Long) {
        fileFor(serverTrackId).delete()
        artworkFileFor(serverTrackId).delete()
        cachedAudioIds -= serverTrackId
        cachedArtworkIds -= serverTrackId
    }

    override suspend fun deleteTrack(serverTrackId: Long): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        val ok = NamiServerClient.deleteTrack(cfg, serverTrackId)
        if (ok) {
            removeFromCache(serverTrackId)
            trackDao.hardDelete("server_$serverTrackId")
            searchRepository.rebuildIndex()
        }
        ok
    }

    override suspend fun deleteFromServerOnly(serverTrackId: Long): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        val cached = cachedFile(serverTrackId)
        if (!NamiServerClient.deleteTrack(cfg, serverTrackId)) return@withContext false

        val mirrorId = "server_$serverTrackId"
        if (cached != null) {
            // Файл уже на устройстве - оставляем и его, и запись о нём. Смены пути достаточно:
            // зеркала чистятся по `path LIKE 'nami-server://%'`, так что строка с реальным путём
            // переживёт синхронизацию и станет обычным локальным треком с тем же id.
            trackDao.setPath(mirrorId, cached.absolutePath)
            cachedAudioIds -= serverTrackId
        } else {
            // Ничего не скачано - на устройстве оставлять нечего, убираем пустое зеркало.
            trackDao.hardDelete(mirrorId)
        }
        searchRepository.rebuildIndex()
        true
    }

    override suspend fun deleteMatchingTracks(tracks: List<ServerTrackMeta>): List<Long> =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext emptyList()
            val cfg = activeConfig() ?: return@withContext emptyList()
            // Сопоставляем разом: matchTracks сам режет список на куски и возвращает ответы
            // в том же порядке. Известный id (зеркало серверной библиотеки) матчить не нужно.
            val matched = NamiServerClient.matchTracks(
                cfg,
                tracks.map {
                    NamiServerClient.MatchTrackRequest(it.title, it.artist, it.durationMs)
                },
            ) ?: return@withContext emptyList()
            val ids = tracks.zip(matched).mapNotNull { (track, byMatch) ->
                track.id.takeIf { it > 0 } ?: byMatch
            }
            deleteTracks(ids.distinct())
        }

    override suspend fun deleteTracks(serverTrackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        if (serverTrackIds.isEmpty()) return@withContext emptyList()
        val cfg = activeConfig() ?: return@withContext emptyList()
        // Чистим локально ровно то, что сервер подтвердил удалённым. Раньше здесь было
        // all-or-nothing по одному Boolean: один уже удалённый трек в выделении означал, что
        // локально не удаляется вообще ничего.
        val deleted = NamiServerClient.deleteTracks(cfg, serverTrackIds)
        if (deleted.isNotEmpty()) {
            for (id in deleted) {
                removeFromCache(id)
                trackDao.hardDelete("server_$id")
            }
            searchRepository.rebuildIndex()
        }
        deleted
    }

    override fun invalidateArtwork(serverTrackId: Long) {
        artworkFileFor(serverTrackId).delete()
        cachedArtworkIds -= serverTrackId
    }

    override suspend fun updateTrack(track: ServerTrackMeta): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        if (!NamiServerClient.updateTrack(cfg, track)) return@withContext false
        listTracks()
        true
    }

    override suspend fun updateMatchingTrack(original: ServerTrackMeta, updated: ServerTrackMeta): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        val id = original.id.takeIf { it > 0 } ?: NamiServerClient.matchTracks(
            cfg,
            listOf(NamiServerClient.MatchTrackRequest(original.title, original.artist, original.durationMs)),
        )?.firstOrNull() ?: return@withContext false
        NamiServerClient.updateTrack(cfg, updated.copy(id = id))
    }

    override suspend fun updateMatchingArtwork(original: ServerTrackMeta, imageUri: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        val id = original.id.takeIf { it > 0 } ?: NamiServerClient.matchTracks(
            cfg,
            listOf(NamiServerClient.MatchTrackRequest(original.title, original.artist, original.durationMs)),
        )?.firstOrNull() ?: return@withContext false
        val uri = android.net.Uri.parse(imageUri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
        NamiServerClient.uploadArtwork(cfg, id, bytes, context.contentResolver.getType(uri) ?: "image/jpeg")
    }

    override suspend fun updateAlbum(album: String, artist: String?, title: String?, year: Int?, updateYear: Boolean, albumArtist: String?, updateAlbumArtist: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        NamiServerClient.updateAlbum(cfg, album, artist, title, year, updateYear, albumArtist, updateAlbumArtist)
    }

    override suspend fun updateArtist(artist: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        NamiServerClient.updateArtist(cfg, artist, name)
    }

    override suspend fun updateArtwork(serverTrackId: Long, imageUri: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext false
        val uri = android.net.Uri.parse(imageUri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        if (!NamiServerClient.uploadArtwork(cfg, serverTrackId, bytes, mime)) return@withContext false
        artworkFileFor(serverTrackId).delete()
        cachedArtworkIds -= serverTrackId
        downloadArtwork(serverTrackId)?.let { file ->
            val id = "server_$serverTrackId"
            trackDao.setArtworkPath(id, file.absolutePath)
            trackDao.findById(id)?.albumId?.let { albumDao.setArtworkPath(it, file.absolutePath) }
        }
        true
    }

    override suspend fun uploadLocalTrack(path: String): String? = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return@withContext null

        // То, что знает приложение, но не обязательно знает файл. При импорте папки название
        // берётся из имени файла, артист и альбом - из имён папок, обложка - из folder.jpg
        // рядом; в теги ничего этого не записывается. Сервер читает только теги, поэтому без
        // подсказки называл трек именем своего временного файла - случайной строкой.
        val local = trackDao.findByPath(path)
        val meta = NamiServerClient.UploadMeta(
            title = local?.title,
            artist = local?.artistId?.let { artistDao.findById(it)?.name },
            album = local?.albumId?.let { albumDao.findById(it)?.title },
            trackNo = local?.trackNo,
        )

        val res = NamiServerClient.uploadTrack(cfg, file, meta) ?: return@withContext null
        val dup = res.optString("duplicate_of").takeIf { it.isNotBlank() && it != "null" }

        // Обложку сервер тоже не увидит, если её нет в тегах. Ставим свою - но только новому
        // треку: у дубля обложка уже есть, и перетирать её нашей незачем.
        if (dup == null) {
            val serverId = res.optLong("track_id", -1).takeIf { it > 0 }
            val artwork = local?.artworkPath ?: local?.albumId?.let { albumDao.findById(it)?.artworkPath }
            if (serverId != null && artwork != null) {
                val bytes = runCatching { File(artwork).readBytes() }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    NamiServerClient.uploadArtwork(cfg, serverId, bytes, "image/webp")
                }
            }
        }

        if (dup != null) "Уже есть на сервере" else "Загружен на сервер"
    }

    override suspend fun createGuestLink(
        title: String,
        tracks: List<Triple<String?, String, Long>>,
        ttlSecs: Long?,
        maxPlays: Int?,
    ): String? = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext null
        val ids = NamiServerClient.matchTrackIds(cfg, tracks)
            ?.filterNotNull()
            ?.distinct()
            ?: return@withContext null
        if (ids.isEmpty()) return@withContext null
        NamiServerClient.createShare(cfg, title, ids, ttlSecs, maxPlays)
    }

    // Расширение не по формату (сервер отдаёт что попросили) - ExoPlayer определяет по содержимому.
    private fun fileFor(id: Long) = File(cacheDir, "$id.audio")
    private fun artworkFileFor(id: Long) = File(cacheDir, "$id.artwork")

    private companion object {
        const val SERVER_PATH_PREFIX = "nami-server://"
    }
}

/**
 * Отбор треков для отправки на сервер (задача "неправильное кол-во при отправке всех"):
 * зеркала серверной библиотеки убираем (слать их некуда), дубликаты одного файла (например
 * трек с несколькими артистами в allTracksOrdered()) схлопываем - иначе он считался бы и
 * отправлялся дважды, а записи об уже удалённых с диска файлах не входят ни в total, ни в
 * "ошибок" при отправке (это не ошибка сервера), а показываются отдельно.
 * Возвращает (реально отправляемые треки, кол-во после дедупа без зеркал, кол-во отсутствующих файлов).
 * internal + вынесено из uploadTracksBackground, чтобы проверяться юнит-тестом без поднятия
 * всего репозитория (Context, DAO и т.д.).
 */
internal fun uploadCandidates(
    allTracks: List<Track>,
    fileExists: (String) -> Boolean = { File(it).exists() },
): Triple<List<Track>, Int, Int> {
    val withoutMirrors = allTracks.filterNot { it.path.startsWith("nami-server://") }
        .distinctBy { it.path }
    val missingFiles = withoutMirrors.count { !fileExists(it.path) }
    val tracks = withoutMirrors.filter { fileExists(it.path) }
    return Triple(tracks, withoutMirrors.size, missingFiles)
}

/**
 * Сопоставление серверных треков с локальными: сохранённая связь по id вперёд метаданных.
 *
 * Порядок здесь и есть весь смысл. Пока связи нет, совпадение ищется по названию, артисту и
 * альбому - но именно их и меняют. Стоило другому устройству переименовать трек, как локальный
 * файл переставал совпадать, считался «новым серверным треком», и в библиотеке появлялось
 * зеркало рядом с собственным файлом. Связь по id переименование переживает.
 *
 * internal и без Room намеренно: правило проверяется тестом без поднятия базы и сети.
 */
internal fun pairServerTracksWithLocal(
    serverTracks: List<ServerTrackMeta>,
    localTracks: List<Track>,
    albumTitleOf: (String) -> String?,
    primaryArtist: (String?) -> String?,
): List<Pair<ServerTrackMeta, Track?>> {
    val linkedByServerId = localTracks.filter { it.serverTrackId != null }.associateBy { it.serverTrackId }
    val taken = mutableSetOf<TrackId>()
    return serverTracks.map { server ->
        val linked = linkedByServerId[server.id]?.takeIf { it.id !in taken }
        val local = linked ?: localTracks.firstOrNull { candidate ->
            // Уже связанный с ДРУГИМ серверным треком локальный файл в кандидаты не годится:
            // иначе два разных серверных трека уцепились бы за одну и ту же строку.
            candidate.id !in taken &&
                (candidate.serverTrackId == null || candidate.serverTrackId == server.id) &&
                DedupKey.matches(
                    title = candidate.title,
                    artistName = primaryArtist(candidate.artistName),
                    albumName = candidate.albumId?.value?.let(albumTitleOf),
                    otherTitle = server.title,
                    otherArtistName = primaryArtist(server.artist),
                    otherAlbumName = server.album,
                )
        }
        if (local != null) taken += local.id
        server to local
    }
}
