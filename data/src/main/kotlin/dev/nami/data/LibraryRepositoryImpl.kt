package dev.nami.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map as pagingMap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.PlayHistoryDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.PlayHistoryEntity
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.player.waveform.AudioFingerprint
import dev.nami.data.mapper.toDomain
import dev.nami.domain.CueSheet
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryHealthReport
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import dev.nami.domain.NativeBridge
import dev.nami.domain.SettingsRepository
import dev.nami.core.tracker.FfmpegNative
import dev.nami.core.tracker.TrackerNative
import dev.nami.player.dsd.DsfToDopWav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject

class LibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val nativeBridge: NativeBridge,
    private val metadataResolver: MetadataResolver,
    private val artworkStore: ArtworkStore,
    private val trashFileStore: TrashFileStore,
    private val folderImportScanner: FolderImportScanner,
    private val lyricsRepository: LyricsRepository,
    private val playHistoryDao: PlayHistoryDao,
    private val settingsRepository: SettingsRepository? = null,
    private val pendingScrobbleDao: dev.nami.core.database.dao.PendingScrobbleDao? = null,
) : LibraryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun libraryHealthReport(): LibraryHealthReport {
        val tracks = trackDao.allOrderedWithArtwork().map { it.toDomain() }
        fun Track.ref() = dev.nami.domain.HealthTrackRef(id, title)

        val withoutArtwork = tracks.filter { it.albumArtworkPath == null }.map { it.ref() }
        val withoutLyrics = tracks.filter { lyricsRepository.lyricsForPath(it.path).first() == null }.map { it.ref() }
        val missingFiles = tracks.filter { !File(it.path).exists() }.map { it.ref() }

        val yearById = albumDao.allIdsAndYears().associate { it.id to it.year }
        val albumsWithoutYear = albumDao.allForIndexing()
            .filter { yearById[it.id] == null }
            .map { dev.nami.domain.HealthAlbumRef(dev.nami.core.model.AlbumId(it.id), it.title) }

        val duplicateGroups = duplicateGroups(tracks).map { group -> group.map { it.ref() } }

        val inconsistentArtistNameGroups = artistDao.allForIndexing()
            .groupBy { it.name.trim().lowercase() }
            .values
            .filter { group -> group.map { it.name }.distinct().size > 1 }
            .map { group -> group.map { dev.nami.domain.HealthArtistRef(dev.nami.core.model.ArtistId(it.id), it.name) } }

        return LibraryHealthReport(
            tracksWithoutArtwork = withoutArtwork,
            tracksWithoutLyrics = withoutLyrics,
            albumsWithoutYear = albumsWithoutYear,
            duplicateGroups = duplicateGroups,
            missingFiles = missingFiles,
            inconsistentArtistNameGroups = inconsistentArtistNameGroups,
        )
    }

    /** Дубли по двум независимым признакам сразу (П.md §23.19):
     * - метаданные: то же название/артист/длительность с точностью до 5 с - тот же сигнал, что
     *   уже использует дедуп при импорте (findDuplicate);
     * - звук: отпечаток RMS-огибающей в пределах Хэммингова порога - ловит один трек в разных
     *   форматах и битрейтах, где ни байты, ни теги не совпадают.
     * Связи объединяются транзитивно (union-find), иначе один и тот же трек попал бы в две
     * группы и "оставить это" удалило бы его из одной, оставив вторую висеть. */
    private suspend fun duplicateGroups(tracks: List<Track>): List<List<Track>> {
        val parent = HashMap<TrackId, TrackId>()
        fun find(x: TrackId): TrackId {
            var root = x
            while (parent[root] != null && parent[root] != root) root = parent[root]!!
            parent[x] = root
            return root
        }
        fun union(a: TrackId, b: TrackId) {
            parent.getOrPut(a) { a }
            parent.getOrPut(b) { b }
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootA] = rootB
        }

        tracks.groupBy { Triple(it.title.trim().lowercase(), it.artistId, it.durationMs / 5000) }
            .values
            .filter { it.size > 1 }
            .forEach { group -> group.drop(1).forEach { union(group.first().id, it.id) } }

        // ponytail: попарный перебор отпечатков, O(n²). Хэммингов порог не разбивается на
        // корзины, поэтому индекса тут нет; при библиотеке в десятки тысяч треков имеет смысл
        // разложить отпечаток на 4 куска по 16 бит и сравнивать только совпавшие по куску.
        val fingerprints = trackDao.allFingerprints()
        for (i in fingerprints.indices) {
            for (j in i + 1 until fingerprints.size) {
                if (AudioFingerprint.matches(fingerprints[i].fingerprint, fingerprints[j].fingerprint)) {
                    union(TrackId(fingerprints[i].id), TrackId(fingerprints[j].id))
                }
            }
        }

        val byId = tracks.associateBy { it.id }
        return parent.keys
            .groupBy { find(it) }
            .values
            .mapNotNull { ids -> ids.mapNotNull { byId[it] }.takeIf { it.size > 1 } }
    }

    override suspend fun trackChains(): Map<String, String> =
        trackDao.allChainLinks().associate { it.id to it.nextId }

    override suspend fun setTrackChain(trackId: TrackId, nextTrackId: TrackId?) {
        trackDao.setChainNext(trackId.value, nextTrackId?.value)
    }

    override suspend fun scanFingerprints(limit: Int): Int = withContext(Dispatchers.IO) {
        val rows = trackDao.tracksWithoutFingerprint(limit)
        rows.forEach { row ->
            // Отпечаток пишется только при успехе: null оставляет трек в очереди на следующий
            // запуск, а не помечает его "просканирован, дублей нет".
            AudioFingerprint.compute(row.path)?.let { trackDao.setAudioFingerprint(row.id, it) }
        }
        trackDao.countWithoutFingerprint()
    }

    override fun tracks(): Flow<PagingData<Track>> =
        Pager(PagingConfig(pageSize = 50)) { trackDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun tracks(sort: dev.nami.domain.TrackSort): Flow<PagingData<Track>> {
        val orderBy = when (sort) {
            dev.nami.domain.TrackSort.DATE_ADDED -> "tracks.dateAdded DESC"
            dev.nami.domain.TrackSort.TITLE -> "tracks.title COLLATE NOCASE ASC"
            dev.nami.domain.TrackSort.ARTIST -> "artistName COLLATE NOCASE ASC, tracks.title COLLATE NOCASE ASC"
            dev.nami.domain.TrackSort.YEAR -> "albums.year DESC"
            dev.nami.domain.TrackSort.DURATION -> "tracks.durationMs DESC"
            dev.nami.domain.TrackSort.PLAY_COUNT -> "tracks.playCount DESC"
            dev.nami.domain.TrackSort.BPM -> "tracks.bpm DESC"
            // Оценка битрейта: колонки нет, но размер/длительность дают порядок не хуже.
            dev.nami.domain.TrackSort.BITRATE -> "(tracks.sizeBytes * 8000.0 / MAX(tracks.durationMs, 1)) DESC"
            dev.nami.domain.TrackSort.RATING -> "tracks.rating DESC"
        }
        val sql = """
            SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
                   artists.name AS artistName
            FROM tracks
            LEFT JOIN albums ON tracks.albumId = albums.id
            LEFT JOIN artists ON tracks.artistId = artists.id
            WHERE tracks.deletedAt IS NULL
            ORDER BY $orderBy
        """.trimIndent()
        return Pager(PagingConfig(pageSize = 50)) {
            trackDao.pagingSourceSorted(androidx.sqlite.db.SimpleSQLiteQuery(sql))
        }.flow.map { pagingData -> pagingData.pagingMap { it.toDomain() } }
    }

    override suspend fun trackYears(): Map<TrackId, Int> =
        trackDao.allTrackYears().associate { TrackId(it.id) to it.year }

    override suspend fun allTracksOrdered(): List<Track> =
        trackDao.allOrderedWithArtwork().map { it.toDomain() }

    override fun allTracksOrderedFlow(): Flow<List<Track>> =
        trackDao.observeAllOrderedWithArtwork().map { rows -> rows.map { it.toDomain() } }

    override fun track(id: TrackId): Flow<Track?> =
        trackDao.observeByIdWithArtwork(id.value).map { it?.toDomain() }

    override fun albums(): Flow<PagingData<AlbumSummary>> =
        Pager(PagingConfig(pageSize = 30)) { albumDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun recentAlbums(limit: Int): Flow<List<AlbumSummary>> =
        albumDao.observeRecentAlbums(limit).map { rows -> rows.map { it.toDomain() } }

    override fun featuredArtists(limit: Int): Flow<List<Artist>> =
        artistDao.observeFeaturedArtists(limit).map { rows -> rows.map { it.toDomain() } }

    override fun artists(): Flow<PagingData<Artist>> =
        Pager(PagingConfig(pageSize = 50)) { artistDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun album(id: AlbumId): Flow<Album?> =
        albumDao.observeById(id.value).map { it?.toDomain() }

    override fun artist(id: ArtistId): Flow<Artist?> = flow {
        emit(artistDao.findByIdWithPhoto(id.value)?.toDomain())
    }

    override fun tracksInAlbum(id: AlbumId): Flow<List<Track>> =
        trackDao.observeTracksForAlbum(id.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun renameTrack(id: TrackId, title: String) {
        trackDao.updateTitle(id.value, title)
    }

    override suspend fun setTrackCover(id: TrackId, imageUri: String) {
        val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
        artworkStore.save(id.value, bytes)?.let { path -> trackDao.updateArtworkPath(id.value, path) }
    }

    override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId {
        val id = java.util.UUID.randomUUID().toString()
        albumDao.insert(
            dev.nami.core.database.entity.AlbumEntity(
                id = id,
                title = title,
                artistId = artistId?.value,
                year = null,
                artworkPath = null,
            ),
        )
        if (artistId != null) {
            albumDao.addArtist(dev.nami.core.database.entity.AlbumArtistCrossRef(id, artistId.value))
        }
        return AlbumId(id)
    }

    override suspend fun deleteAlbum(id: AlbumId) {
        albumDao.softDelete(id.value, deletedAt = System.currentTimeMillis())
        trackDao.trackIdsForAlbum(id.value).forEach { deleteTrack(TrackId(it)) }
    }

    override suspend fun renameAlbum(id: AlbumId, title: String) {
        albumDao.updateTitle(id.value, title)
    }

    override suspend fun setAlbumCover(id: AlbumId, imageUri: String) {
        val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
        artworkStore.save(id.value, bytes)?.let { path -> albumDao.updateArtworkPath(id.value, path) }
    }

    override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) {
        albumDao.setIsSingle(id.value, isSingle)
    }

    override suspend fun setAlbumYear(id: AlbumId, year: Int?) {
        albumDao.setYear(id.value, year)
    }

    override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) {
        // The single-artist picker replaces the whole credit list with just this one artist --
        // addAlbumArtist/removeAlbumArtist below are the ones that add to/trim an existing list.
        albumDao.setArtistId(id.value, artistId?.value)
        albumDao.clearArtists(id.value)
        if (artistId != null) albumDao.addArtist(dev.nami.core.database.entity.AlbumArtistCrossRef(id.value, artistId.value))
    }

    override fun albumArtists(id: AlbumId): Flow<List<Artist>> =
        albumDao.observeArtistsForAlbum(id.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) {
        albumDao.addArtist(dev.nami.core.database.entity.AlbumArtistCrossRef(id.value, artistId.value))
    }

    override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) {
        albumDao.removeArtist(id.value, artistId.value)
    }

    override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) {
        val previousAlbumId = trackDao.findById(trackId.value)?.albumId
        trackDao.setAlbumId(trackId.value, albumId.value)
        syncAlbumIsSingle(albumId.value)
        previousAlbumId?.let { syncAlbumIsSingle(it) }
    }

    override suspend fun removeTrackFromAlbum(trackId: TrackId) {
        val previousAlbumId = trackDao.findById(trackId.value)?.albumId
        trackDao.setAlbumId(trackId.value, null)
        previousAlbumId?.let { syncAlbumIsSingle(it) }
    }

    /** Auto-tags an album "single" the moment it has exactly one (non-deleted) track, and clears
     * the tag the moment it no longer does - called after every mutation that can change an
     * album's track count (add/remove/delete a track, import). Doesn't touch albums the user
     * never marked - an album with 2+ tracks the user manually flagged single (if that's ever
     * allowed elsewhere) also gets un-flagged here, since "single" is defined purely by track
     * count for this app, not a separate manual-only concept. */
    private suspend fun syncAlbumIsSingle(albumId: String) {
        albumDao.setIsSingle(albumId, trackDao.countByAlbum(albumId) == 1)
    }

    override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) {
        trackDao.setArtistId(trackId.value, artistId.value)
    }

    override suspend fun removeTrackFromArtist(trackId: TrackId) {
        trackDao.setArtistId(trackId.value, null)
    }

    override suspend fun renameArtist(id: ArtistId, name: String) {
        artistDao.updateName(id.value, name)
    }

    override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) {
        val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
        artworkStore.save(id.value, bytes)?.let { path -> artistDao.updatePhotoPath(id.value, path) }
    }

    override fun tracksByArtist(id: ArtistId): Flow<List<Track>> =
        trackDao.tracksForArtist(id.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun incrementPlayCount(id: TrackId) {
        trackDao.incrementPlayCount(id.value)
        trackDao.setFirstPlayedIfUnset(id.value, System.currentTimeMillis())
    }

    override suspend fun setTrackRating(id: TrackId, rating: Int?) {
        trackDao.updateRating(id.value, rating?.coerceIn(1, 5))
    }

    override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {
        playHistoryDao.insert(PlayHistoryEntity(trackId = id.value, playedAt = playedAt, durationMs = durationMs))
        val settings = settingsRepository
        if (settings != null && settings.namiServerToken.value != null && settings.namiServerUrl.value.isNotBlank()) {
            val cfg = serverConfig()
            scope.launch(Dispatchers.IO) {
                var serverTrackId = if (id.value.startsWith("server_")) {
                    id.value.removePrefix("server_").toLongOrNull()
                } else {
                    id.value.toLongOrNull()
                }
                if (serverTrackId == null && cfg != null) {
                    val track = trackDao.findById(id.value)
                    if (track != null) {
                        val artistName = track.artistId?.let { artistDao.findById(it)?.name }
                        val matches = runCatching {
                            NamiServerClient.matchTrackIds(
                                cfg,
                                listOf(Triple(artistName, track.title, track.durationMs)),
                            )
                        }.getOrNull()
                        serverTrackId = matches?.firstOrNull()
                    }
                }
                var sent = false
                if (serverTrackId != null && cfg != null) {
                    sent = runCatching { NamiServerClient.scrobble(cfg, serverTrackId, playedAt / 1000) }.getOrDefault(false)
                }
                if (!sent) {
                    pendingScrobbleDao?.insert(
                        dev.nami.core.database.entity.PendingScrobbleEntity(
                            trackId = id.value,
                            serverTrackId = serverTrackId,
                            playedAt = playedAt,
                            durationMs = durationMs,
                        )
                    )
                }
            }
        }
    }

    private fun serverConfig(): NamiServerClient.Config? {
        val settings = settingsRepository ?: return null
        val token = settings.namiServerToken.value?.takeIf { it.isNotBlank() } ?: return null
        val urls = settings.namiServerUrl.value
            .split('\n', ',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return null
        val cert = settings.namiServerCertSha256.value
        return NamiServerClient.Config(urls.first(), token, cert, urls)
    }

    // Aggregated in Kotlin, not SQL - day boundaries use the device's local timezone via
    // java.time, simplest to get right there rather than in a SQLite date() expression.
    override suspend fun dailyListeningMinutes(days: Int): List<dev.nami.domain.DayActivity> {
        val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return playHistoryDao.since(since)
            .groupBy { java.time.Instant.ofEpochMilli(it.playedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay() }
            .map { (epochDay, rows) -> dev.nami.domain.DayActivity(epochDay, (rows.sumOf { it.durationMs } / 60_000).toInt()) }
    }

    // Header numbers on the Статистика screen - actually-listened, not library totals (see
    // ListeningSummary's own doc for why).
    override suspend fun listeningSummary(days: Int): dev.nami.domain.ListeningSummary {
        val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val rows = playHistoryDao.since(since)
        val totalMinutes = (rows.sumOf { it.durationMs } / 60_000).toInt()
        val distinctTrackIds = rows.map { it.trackId }.distinct()
        val artistIds = distinctTrackIds.mapNotNull { trackDao.findById(it)?.artistId }.distinct()
        return dev.nami.domain.ListeningSummary(totalMinutes, distinctTrackIds.size, artistIds.size)
    }

    override suspend fun hourOfDayMinutes(days: Int): List<Int> {
        val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val buckets = IntArray(24)
        playHistoryDao.since(since).forEach { row ->
            val hour = java.time.Instant.ofEpochMilli(row.playedAt).atZone(java.time.ZoneId.systemDefault()).hour
            buckets[hour] += (row.durationMs / 60_000).toInt()
        }
        return buckets.toList()
    }

    override suspend fun topTracks(days: Int, limit: Int): List<dev.nami.domain.TopTrackStat> {
        val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val counts = playHistoryDao.since(since).groupingBy { it.trackId }.eachCount()
        return counts.entries.sortedByDescending { it.value }.take(limit).mapNotNull { (trackId, count) ->
            val entity = trackDao.findByIdWithArtwork(trackId) ?: return@mapNotNull null
            dev.nami.domain.TopTrackStat(TrackId(trackId), entity.track.title, entity.artistName, entity.albumArtworkPath, count)
        }
    }

    override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {
        trackDao.updateReplayGain(id.value, gainDb)
    }

    override suspend fun setTrackNote(id: TrackId, note: String?) {
        trackDao.updateNote(id.value, note?.takeIf { it.isNotBlank() })
    }

    override suspend fun batchEditTracks(ids: List<TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) {
        // Resolved once for the whole batch, not per track - otherwise "same artist name" would
        // still risk create-then-find races across tracks (find-or-create isn't atomic here).
        val resolvedArtistId = artistName?.takeIf { it.isNotBlank() }?.let { metadataResolver.resolveArtist(it) }
        val resolvedAlbumId = albumName?.takeIf { it.isNotBlank() }?.let { metadataResolver.resolveAlbum(it, resolvedArtistId, year) }

        for (id in ids) {
            if (resolvedArtistId != null) trackDao.setArtistId(id.value, resolvedArtistId)
            if (resolvedAlbumId != null) {
                trackDao.setAlbumId(id.value, resolvedAlbumId)
            } else if (year != null) {
                // No new album named - apply the year to whatever album this track is already on.
                trackDao.findById(id.value)?.albumId?.let { albumDao.setYear(it, year) }
            }
            if (genre != null) trackDao.updateGenre(id.value, genre.takeIf { it.isNotBlank() })
        }
    }

    override suspend fun searchMusicBrainz(title: String, artistName: String?): List<dev.nami.domain.MusicBrainzCandidate> =
        withContext(Dispatchers.IO) { MusicBrainzClient.search(title, artistName) }

    override suspend fun incrementSkipCount(id: TrackId) {
        trackDao.incrementSkipCount(id.value)
    }

    override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {
        trackDao.updateBpmKey(id.value, bpm, musicalKey)
    }

    override suspend fun deleteTrack(id: TrackId) {
        val track = trackDao.findById(id.value) ?: return
        val trashedPath = trashFileStore.moveToTrash(id.value, track.path) ?: track.path
        trackDao.setDeletedAt(id.value, deletedAt = System.currentTimeMillis(), path = trashedPath)
        track.albumId?.let { syncAlbumIsSingle(it) }
    }

    override suspend fun deleteTracks(ids: List<TrackId>) {
        ids.forEach { deleteTrack(it) }
    }

    override fun albumsByArtist(id: ArtistId): Flow<List<AlbumSummary>> = flow {
        emit(
            albumDao.albumsByArtist(id.value).map {
                AlbumSummary(
                    id = AlbumId(it.id),
                    title = it.title,
                    artistName = null,
                    artworkPath = it.artworkPath,
                    year = it.year,
                    isSingle = it.isSingle,
                )
            },
        )
    }

    override suspend fun import(source: ImportSource): Flow<ImportProgress> = when (source) {
        is ImportSource.Files -> importFiles(source.uris)
        is ImportSource.Folder -> importFolder(source.treeUri)
        is ImportSource.Zip -> importZip(source.uri)
    }

    private fun importFiles(uriStrings: List<String>): Flow<ImportProgress> = flow {
        val uris = uriStrings.map { it.toUri() }
        val musicDir = File(context.filesDir, "music").apply { mkdirs() }
        val resolver = context.contentResolver

        uris.forEachIndexed { index, uri ->
            val result = copyAndIndex(resolver, uri, musicDir)
            result?.albumId?.let { syncAlbumIsSingle(it) }
            emit(ImportProgress(done = index + 1, total = uris.size))
        }
    }.flowOn(Dispatchers.IO)

    private fun importFolder(treeUriString: String): Flow<ImportProgress> = flow {
        emitAll(importFolderFromGroups(folderImportScanner.scan(treeUriString.toUri())))
    }.flowOn(Dispatchers.IO)

    // П.md §2 "Импорт .zip-архивов с распаковкой на лету" - extracts each audio entry to a
    // scratch file in cacheDir (deleted right after), then feeds it through the exact same
    // copyAndIndex path as a picked file (a file:// Uri resolves fine through ContentResolver for
    // reading, no FileProvider needed). Non-audio entries (readme, cover art sitting loose in the
    // zip) are skipped rather than rejecting the whole archive.
    private fun importZip(uriString: String): Flow<ImportProgress> = flow {
        val musicDir = File(context.filesDir, "music").apply { mkdirs() }
        val resolver = context.contentResolver
        val scratchDir = File(context.cacheDir, "zip_import").apply { mkdirs() }

        // Раньше все аудиозаписи архива сначала целиком читались в память (List<ByteArray>) и
        // только потом обрабатывались - на архиве заметного размера (альбом, тем более whole-
        // library бэкап) это OutOfMemoryError раньше, чем хоть один трек успевал добавиться.
        // ZipInputStream и так уже потоковый - каждая запись сразу льётся на диск и индексируется
        // по одной, не накапливаясь.
        var total = 0
        resolver.openInputStream(uriString.toUri())?.use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isAudioFileName(entry.name)) total++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        var done = 0
        resolver.openInputStream(uriString.toUri())?.use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isAudioFileName(entry.name)) {
                        val scratchFile = File(scratchDir, "${UUID.randomUUID()}_${entry.name.substringAfterLast('/')}")
                        scratchFile.outputStream().use { out -> zip.copyTo(out) }
                        try {
                            val result = copyAndIndex(resolver, android.net.Uri.fromFile(scratchFile), musicDir)
                            result?.albumId?.let { syncAlbumIsSingle(it) }
                        } finally {
                            scratchFile.delete()
                        }
                        done++
                        emit(ImportProgress(done = done, total = total))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // Всё, что Media3 не умеет само (трекерные модули, чиптюны, APE/WavPack/TAK/Musepack),
    // конвертируется в .wav при импорте - см. copyAndIndex.
    private fun isAudioFileName(name: String): Boolean =
        (listOf(".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".aiff", ".dsf", ".dff") +
            TrackerNative.extensions + FfmpegNative.extensions)
            .any { name.endsWith(it, ignoreCase = true) }

    internal fun importFolderFromGroups(groups: List<AudioGroup>): Flow<ImportProgress> = flow {
        val musicDir = File(context.filesDir, "music").apply { mkdirs() }
        val resolver = context.contentResolver
        val total = groups.sumOf { it.audioFiles.size }
        var done = 0

        for (group in groups) {
            var albumIdForGroup: String? = null
            for (doc in group.audioFiles) {
                val result = copyAndIndex(
                    resolver, doc.uri, musicDir,
                    fallbackArtist = group.artistFolderName,
                    fallbackAlbum = group.albumFolderName,
                    lyricsDoc = folderImportScanner.findLyrics(doc),
                    cueDoc = folderImportScanner.findCue(doc),
                )
                if (albumIdForGroup == null) albumIdForGroup = result?.albumId
                done++
                emit(ImportProgress(done = done, total = total))
            }

            val albumId = albumIdForGroup
            if (albumId != null) syncAlbumIsSingle(albumId)
            if (albumId != null && albumDao.findById(albumId)?.artworkPath == null) {
                val coverDoc = folderImportScanner.findFolderCover(group.sourceDir)
                val bytes = coverDoc?.let { resolver.openInputStream(it.uri)?.use { stream -> stream.readBytes() } }
                if (bytes != null) {
                    artworkStore.save(albumId, bytes)?.let { path -> albumDao.setArtworkPath(albumId, path) }
                }
            }

            val artistDir = group.artistDir
            if (artistDir != null && group.artistFolderName != null) {
                val artistEntity = artistDao.findByName(group.artistFolderName)
                if (artistEntity != null && artistEntity.photoPath == null) {
                    val photoDoc = folderImportScanner.findFolderCover(artistDir)
                    val bytes = photoDoc?.let { resolver.openInputStream(it.uri)?.use { stream -> stream.readBytes() } }
                    if (bytes != null) {
                        artworkStore.save(artistEntity.id, bytes)?.let { path -> artistDao.setPhotoPath(artistEntity.id, path) }
                    }
                }
            }
        }
    }

    private data class CopyAndIndexResult(val trackId: String, val albumId: String?)

    private suspend fun copyAndIndex(
        resolver: ContentResolver,
        uri: Uri,
        musicDir: File,
        fallbackArtist: String? = null,
        fallbackAlbum: String? = null,
        lyricsDoc: DocumentFile? = null,
        cueDoc: DocumentFile? = null,
    ): CopyAndIndexResult? {
        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment
        val extension = resolver.getType(uri)?.substringAfterLast('/') ?: "audio"
        var destination = File(musicDir, "${UUID.randomUUID()}.$extension")

        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        // Этап 10: DSD import via DoP, wired to a real container parser (see DsfToDopWav's own
        // doc for why this converts at import time instead of a custom streaming Extractor).
        // Falls through to indexing the raw .dsf as-is (native tag reader will likely find
        // nothing useful in it, same as any other unrecognized format) if conversion fails --
        // never crashes the import over one bad/unsupported DSD file.
        if (displayName?.endsWith(".dsf", ignoreCase = true) == true) {
            val dsfBytes = destination.readBytes()
            val wavBytes = DsfToDopWav.convert(dsfBytes)
            if (wavBytes != null) {
                val wavDestination = File(musicDir, "${destination.nameWithoutExtension}.wav")
                wavDestination.writeBytes(wavBytes)
                destination.delete()
                destination = wavDestination
            }
        }

        // Трекерные модули (.mod/.xm/.it/.s3m, libopenmpt) и чиптюны консолей (.nsf/.spc/.vgm/
        // .gbs, game-music-emu) - тот же приём, что выше с DSD: рендерим один раз в .wav, дальше
        // файл идёт по обычному пути. Если нативная библиотека не собралась или формат ей не
        // знаком - оставляем файл как есть, импорт из-за этого не падает.
        if (TrackerNative.extensions.any { displayName?.endsWith(it, ignoreCase = true) == true }) {
            val wavDestination = File(musicDir, "${destination.nameWithoutExtension}.wav")
            if (TrackerNative.renderToWav(destination.path, wavDestination.path)) {
                destination.delete()
                destination = wavDestination
            }
        }

        // APE/WavPack/TAK/Musepack: у Media3 для этих контейнеров нет ни декодера, ни Extractor'а,
        // поэтому декодируем своим минимальным FFmpeg (native/jni/build_ffmpeg.sh). Раньше .ape
        // и .wv импортировались и молча не игрались - теперь либо играются, либо остаются как
        // есть, если .so не собрана.
        if (FfmpegNative.extensions.any { displayName?.endsWith(it, ignoreCase = true) == true }) {
            val wavDestination = File(musicDir, "${destination.nameWithoutExtension}.wav")
            if (FfmpegNative.decodeToWav(destination.path, wavDestination.path)) {
                destination.delete()
                destination = wavDestination
            }
        }

        // Same basename convention LyricsRepositoryImpl reads from (sibling .lrc next to the
        // audio file) - copied alongside so a folder import with lyrics already sitting next to
        // the tracks doesn't need a separate manual "load from file" step.
        if (lyricsDoc != null) {
            resolver.openInputStream(lyricsDoc.uri)?.use { input ->
                File(musicDir, "${destination.nameWithoutExtension}.lrc").outputStream().use { output -> input.copyTo(output) }
            }
        }

        val tags = nativeBridge.readTags(destination.path)

        // Only when a sidecar wasn't already copied above (a real file next to the track wins
        // over whatever's embedded). Plan's source order is local .lrc -> tag -> LRCLIB -> manual.
        val embeddedLyrics = tags?.lyrics
        if (lyricsDoc == null && embeddedLyrics != null) {
            lyricsRepository.importLyricsFile(destination.path, embeddedLyrics)
        }

        val artistId = metadataResolver.resolveArtist(tags?.artist ?: tags?.albumArtist ?: fallbackArtist)
        val albumId = metadataResolver.resolveAlbum(tags?.album ?: fallbackAlbum, artistId, tags?.year)
        val fallbackTitle = (displayName ?: "unknown").substringBeforeLast('.')
        val title = tags?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val durationMs = tags?.durationMs?.takeIf { it > 0 } ?: readDurationMs(destination.path)

        // Same title/artist/album/duration as an already-imported track - treat the freshly
        // copied file as a duplicate of it and discard the copy instead of indexing it again.
        if (trackDao.findDuplicate(title, artistId, albumId, durationMs) != null) {
            destination.delete()
            return null
        }

        // CRC32, not a real audio fingerprint (chromaprint) - cheap, exact-byte identity check
        // that strengthens LibraryHealthReport's title/artist/duration dedup heuristic for the
        // common "same file, re-tagged" case, honestly not claiming to catch different rips of
        // the same recording.
        val fileHash = runCatching {
            val crc = java.util.zip.CRC32()
            destination.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                }
            }
            crc.value.toString(16)
        }.getOrNull()

        val trackId = UUID.randomUUID().toString()
        val artwork = tags?.artwork
        var trackArtworkPath: String? = null
        if (artwork != null) {
            val artworkKey = albumId ?: trackId
            val savedPath = artworkStore.save(artworkKey, artwork)
            if (albumId != null) {
                savedPath?.let { path -> albumDao.setArtworkPath(albumId, path) }
            } else {
                trackArtworkPath = savedPath
            }
        }

        // Хвост группы C "CUE-поддержка" - .cue рядом с образом альбома разбивает этот один
        // физический файл на несколько строк tracks, все с одним и тем же path (см.
        // TrackEntity.path's index, больше не unique) и своими cueStartMs/cueEndMs. Меньше 2
        // разобранных треков (пустой/битый .cue) - откатывается на обычный один трек на файл.
        val cueTracks = cueDoc?.let { doc ->
            runCatching { resolver.openInputStream(doc.uri)?.use { it.bufferedReader().readText() } }.getOrNull()
                ?.let(CueSheet::parse)
        }?.takeIf { it.size >= 2 }

        if (cueTracks != null) {
            val dateAdded = System.currentTimeMillis()
            cueTracks.forEachIndexed { index, cue ->
                val cueEndMs = cueTracks.getOrNull(index + 1)?.startMs
                val cueArtistId = cue.performer?.let { metadataResolver.resolveArtist(it) } ?: artistId
                trackDao.insertAll(
                    listOf(
                        TrackEntity(
                            id = UUID.randomUUID().toString(),
                            title = cue.title,
                            artistId = cueArtistId,
                            albumId = albumId,
                            trackNo = cue.trackNo,
                            discNo = tags?.discNo,
                            durationMs = (cueEndMs ?: durationMs) - cue.startMs,
                            path = destination.path,
                            format = extension,
                            sizeBytes = destination.length(),
                            dateAdded = dateAdded,
                            lastPlayed = null,
                            playCount = 0,
                            genre = tags?.genre,
                            artworkPath = trackArtworkPath,
                            sampleRateHz = tags?.sampleRateHz,
                            bitDepth = tags?.bitDepth,
                            channels = tags?.channels,
                            fileHash = fileHash,
                            cueStartMs = cue.startMs,
                            cueEndMs = cueEndMs,
                        ),
                    ),
                )
            }
            return CopyAndIndexResult(trackId = trackId, albumId = albumId)
        }

        trackDao.insertAll(
            listOf(
                TrackEntity(
                    id = trackId,
                    title = title,
                    artistId = artistId,
                    albumId = albumId,
                    trackNo = tags?.trackNo,
                    discNo = tags?.discNo,
                    durationMs = durationMs,
                    path = destination.path,
                    format = extension,
                    sizeBytes = destination.length(),
                    dateAdded = System.currentTimeMillis(),
                    lastPlayed = null,
                    playCount = 0,
                    genre = tags?.genre,
                    artworkPath = trackArtworkPath,
                    sampleRateHz = tags?.sampleRateHz,
                    bitDepth = tags?.bitDepth,
                    channels = tags?.channels,
                    fileHash = fileHash,
                ),
            ),
        )
        return CopyAndIndexResult(trackId = trackId, albumId = albumId)
    }

    // Fallbacks for files lofty can't parse (or that carry no duration in their tag).
    private fun readDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return null
    }
}
