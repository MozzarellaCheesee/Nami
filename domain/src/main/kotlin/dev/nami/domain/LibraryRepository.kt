package dev.nami.domain

import androidx.paging.PagingData
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun tracks(): Flow<PagingData<Track>>
    fun track(id: TrackId): Flow<Track?>
    suspend fun import(source: ImportSource): Flow<ImportProgress>
}

data class ImportProgress(val done: Int, val total: Int)
