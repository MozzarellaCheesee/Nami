package dev.nami.domain

import androidx.paging.PagingData
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun tracks(): Flow<PagingData<Track>>
    fun track(id: TrackId): Flow<Track?>
    fun albums(): Flow<PagingData<AlbumSummary>>
    fun artists(): Flow<PagingData<Artist>>
    fun album(id: AlbumId): Flow<Album?>
    fun artist(id: ArtistId): Flow<Artist?>
    fun tracksInAlbum(id: AlbumId): Flow<List<Track>>
    fun tracksByArtist(id: ArtistId): Flow<List<Track>>
    fun albumsByArtist(id: ArtistId): Flow<List<AlbumSummary>>
    suspend fun import(source: ImportSource): Flow<ImportProgress>
    suspend fun deleteTrack(id: TrackId)
}

data class ImportProgress(val done: Int, val total: Int)
