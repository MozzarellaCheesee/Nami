package dev.nami.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.data.mapper.toDomain
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject

class LibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
) : LibraryRepository {

    override fun tracks(): Flow<PagingData<Track>> =
        Pager(PagingConfig(pageSize = 50)) { trackDao.pagingSource() }
            .flow
            .map { pagingData -> pagingData.map { it.toDomain() } }

    override fun track(id: TrackId): Flow<Track?> = flow {
        emit(trackDao.findById(id.value)?.toDomain())
    }

    override suspend fun import(source: ImportSource): Flow<ImportProgress> = flow {
        val uris = (source as ImportSource.Files).uris.map { it.toUri() }
        val musicDir = File(context.filesDir, "music").apply { mkdirs() }
        val resolver = context.contentResolver

        uris.forEachIndexed { index, uri ->
            copyAndIndex(resolver, uri, musicDir)
            emit(ImportProgress(done = index + 1, total = uris.size))
        }
    }

    private suspend fun copyAndIndex(resolver: ContentResolver, uri: Uri, musicDir: File) {
        val extension = resolver.getType(uri)?.substringAfterLast('/') ?: "audio"
        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "unknown"
        val destination = File(musicDir, "${UUID.randomUUID()}.$extension")

        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return

        if (trackDao.findByPath(destination.path) != null) return

        val durationMs = readDurationMs(destination.path)
        val title = displayName.substringBeforeLast('.')

        trackDao.insertAll(
            listOf(
                TrackEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    artistId = null,
                    albumId = null,
                    trackNo = null,
                    discNo = null,
                    durationMs = durationMs,
                    path = destination.path,
                    format = extension,
                    sizeBytes = destination.length(),
                    dateAdded = System.currentTimeMillis(),
                    lastPlayed = null,
                    playCount = 0,
                ),
            ),
        )
    }

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
