package dev.nami.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.AlbumDao
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerLibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val metadataResolver: MetadataResolver,
    private val searchRepository: SearchRepository,
) : ServerLibraryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadJob: Job? = null
    private val _uploadProgress = MutableStateFlow<String?>(null)
    override val uploadProgress: StateFlow<String?> = _uploadProgress

    override fun clearUploadProgress() {
        _uploadProgress.value = null
    }

    override fun uploadTracksBackground(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            val cfg = activeConfig()
            if (cfg == null) {
                _uploadProgress.value = "Сервер не подключён"
                return@launch
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
                return@launch
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

            for ((index, track) in toUpload.withIndex()) {
                val res = uploadLocalTrack(track.path)
                if (res == "Уже есть на сервере") duplicates++
                else if (res != null) uploaded++
                else failed++

                _uploadProgress.value = if (alreadyOnServer > 0) {
                    "На сервере уже $alreadyOnServer из $total. Отправка новых: ${index + 1}/$needUploadCount…"
                } else {
                    "Отправка на сервер: ${index + 1}/$needUploadCount…"
                }
            }

            _uploadProgress.value = buildString {
                append("Выгрузка завершена: ")
                if (uploaded > 0) append("загружено $uploaded ")
                val totalAlready = alreadyOnServer + duplicates
                if (totalAlready > 0) append("(уже было на сервере: $totalAlready) ")
                if (failed > 0) append("ошибок $failed")
            }.trim()
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
    private val cacheDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "ServerCache").apply { mkdirs() }
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
            val arr = NamiServerClient.tracks(cfg, limit, offset) ?: return@withContext null
            val tracks = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1)
                if (id < 0) return@mapNotNull null
                ServerTrackMeta(
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
            mirrorIntoLibrary(tracks)
            tracks
        }

    /** Серверные записи живут в общей Room-библиотеке, поэтому все существующие очереди,
     * shuffle, Home и поиск получают их без специальных веток в UI. */
    private suspend fun mirrorIntoLibrary(serverTracks: List<ServerTrackMeta>) {
        val localTracks = trackDao.allOrderedWithArtwork()
            .map { it.toDomain() }
            .filterNot { it.path.startsWith(SERVER_PATH_PREFIX) }
        val unique = serverTracks.filterNot { server ->
            localTracks.any { local ->
                local.title.equals(server.title, ignoreCase = true) &&
                    local.artistName.orEmpty().equals(server.artist, ignoreCase = true) &&
                    kotlin.math.abs(local.durationMs - server.durationMs) <= 2_000
            }
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

    override suspend fun downloadTrack(serverTrackId: Long): File? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val dest = fileFor(serverTrackId)
            val artDest = artworkFileFor(serverTrackId)
            if (dest.exists() && dest.length() > 0) {
                if (!artDest.exists() || artDest.length() == 0L) {
                    runCatching { NamiServerClient.downloadArtwork(cfg, serverTrackId, artDest) }
                }
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
            // Параллельно подтягиваем и сохраняем обложку трека для офлайн-режима
            if (!artDest.exists() || artDest.length() == 0L) {
                runCatching { NamiServerClient.downloadArtwork(cfg, serverTrackId, artDest) }
            }
            dest
        }

    override fun cachedFile(serverTrackId: Long): File? =
        fileFor(serverTrackId).takeIf { it.exists() && it.length() > 0 }

    override fun cachedArtwork(serverTrackId: Long): File? =
        artworkFileFor(serverTrackId).takeIf { it.exists() && it.length() > 0 }

    override suspend fun downloadArtwork(serverTrackId: Long): File? = withContext(Dispatchers.IO) {
        cachedArtwork(serverTrackId)?.let { return@withContext it }
        val cfg = activeConfig() ?: return@withContext null
        val dest = artworkFileFor(serverTrackId)
        if (NamiServerClient.downloadArtwork(cfg, serverTrackId, dest)) dest else null
    }

    override fun cachedTrackIds(): Set<Long> =
        cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".audio") }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    override fun removeFromCache(serverTrackId: Long) {
        fileFor(serverTrackId).delete()
        artworkFileFor(serverTrackId).delete()
    }

    override suspend fun uploadLocalTrack(path: String): String? = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return@withContext null
        val res = NamiServerClient.uploadTrack(cfg, file) ?: return@withContext null
        val dup = res.optString("duplicate_of").takeIf { it.isNotBlank() && it != "null" }
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
