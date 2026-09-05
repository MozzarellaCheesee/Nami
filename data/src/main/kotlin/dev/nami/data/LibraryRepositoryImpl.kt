package dev.nami.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
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
import dev.nami.domain.NativeBridge
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
) : LibraryRepository {

    override fun tracks(): Flow<PagingData<Track>> =
        Pager(PagingConfig(pageSize = 50)) { trackDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun track(id: TrackId): Flow<Track?> = flow {
        emit(trackDao.findById(id.value)?.toDomain())
    }

    override fun albums(): Flow<PagingData<AlbumSummary>> =
        Pager(PagingConfig(pageSize = 30)) { albumDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun artists(): Flow<PagingData<Artist>> =
        Pager(PagingConfig(pageSize = 50)) { artistDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.pagingMap { it.toDomain() } }

    override fun album(id: AlbumId): Flow<Album?> = flow {
        emit(albumDao.findById(id.value)?.toDomain())
    }

    override fun artist(id: ArtistId): Flow<Artist?> = flow {
        emit(artistDao.findById(id.value)?.toDomain())
    }

    override fun tracksInAlbum(id: AlbumId): Flow<List<Track>> = flow {
        emit(trackDao.tracksForAlbum(id.value).map { it.toDomain() })
    }

    override fun tracksByArtist(id: ArtistId): Flow<List<Track>> = flow {
        emit(trackDao.tracksForArtist(id.value).map { it.toDomain() })
    }

    override suspend fun deleteTrack(id: TrackId) {
        val track = trackDao.findById(id.value) ?: return
        val trashedPath = trashFileStore.moveToTrash(id.value, track.path) ?: track.path
        trackDao.setDeletedAt(id.value, deletedAt = System.currentTimeMillis(), path = trashedPath)
    }

    override suspend fun deleteTracks(ids: List<TrackId>) {
        ids.forEach { deleteTrack(it) }
    }

    override fun albumsByArtist(id: ArtistId): Flow<List<AlbumSummary>> = flow {
        emit(
            albumDao.albumsByArtist(id.value).map {
                AlbumSummary(id = AlbumId(it.id), title = it.title, artistName = null, artworkPath = it.artworkPath)
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
            copyAndIndex(resolver, uri, musicDir)
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
                )
                if (albumIdForGroup == null) albumIdForGroup = result?.albumId
                done++
                emit(ImportProgress(done = done, total = total))
            }

            val albumId = albumIdForGroup ?: continue
            if (albumDao.findById(albumId)?.artworkPath == null) {
                val coverDoc = folderImportScanner.findFolderCover(group.sourceDir) ?: continue
                val bytes = resolver.openInputStream(coverDoc.uri)?.use { it.readBytes() } ?: continue
                artworkStore.save(albumId, bytes)?.let { path -> albumDao.setArtworkPath(albumId, path) }
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
    ): CopyAndIndexResult? {
        val extension = resolver.getType(uri)?.substringAfterLast('/') ?: "audio"
        val destination = File(musicDir, "${UUID.randomUUID()}.$extension")

        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        if (trackDao.findByPath(destination.path) != null) return null

        val tags = nativeBridge.readTags(destination.path)
        val artistId = metadataResolver.resolveArtist(tags?.artist ?: tags?.albumArtist ?: fallbackArtist)
        val albumId = metadataResolver.resolveAlbum(tags?.album ?: fallbackAlbum, artistId, tags?.year)
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

        val fallbackTitle = (queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "unknown")
            .substringBeforeLast('.')

        trackDao.insertAll(
            listOf(
                TrackEntity(
                    id = trackId,
                    title = tags?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                    artistId = artistId,
                    albumId = albumId,
                    trackNo = tags?.trackNo,
                    discNo = tags?.discNo,
                    durationMs = tags?.durationMs?.takeIf { it > 0 } ?: readDurationMs(destination.path),
                    path = destination.path,
                    format = extension,
                    sizeBytes = destination.length(),
                    dateAdded = System.currentTimeMillis(),
                    lastPlayed = null,
                    playCount = 0,
                    genre = tags?.genre,
                    artworkPath = trackArtworkPath,
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
