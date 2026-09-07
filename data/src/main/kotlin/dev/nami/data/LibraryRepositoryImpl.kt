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
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.data.mapper.toDomain
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import dev.nami.domain.NativeBridge
import dev.nami.player.dsd.DsfToDopWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
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
) : LibraryRepository {

    override fun tracks(): Flow<PagingData<Track>> =
        Pager(PagingConfig(pageSize = 50)) { trackDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override suspend fun allTracksOrdered(): List<Track> =
        trackDao.allOrderedWithArtwork().map { it.toDomain() }

    override fun track(id: TrackId): Flow<Track?> = flow {
        emit(trackDao.findByIdWithArtwork(id.value)?.toDomain())
    }

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
     * the tag the moment it no longer does -- called after every mutation that can change an
     * album's track count (add/remove/delete a track, import). Doesn't touch albums the user
     * never marked -- an album with 2+ tracks the user manually flagged single (if that's ever
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
    }

    override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {
        trackDao.updateReplayGain(id.value, gainDb)
    }

    override suspend fun setTrackNote(id: TrackId, note: String?) {
        trackDao.updateNote(id.value, note?.takeIf { it.isNotBlank() })
    }

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

        // Same basename convention LyricsRepositoryImpl reads from (sibling .lrc next to the
        // audio file) -- copied alongside so a folder import with lyrics already sitting next to
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

        // Same title/artist/album/duration as an already-imported track -- treat the freshly
        // copied file as a duplicate of it and discard the copy instead of indexing it again.
        if (trackDao.findDuplicate(title, artistId, albumId, durationMs) != null) {
            destination.delete()
            return null
        }

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
