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
    /** Snapshot of every non-deleted track, same order as [tracks], for building a full playback queue. */
    suspend fun allTracksOrdered(): List<Track>
    fun track(id: TrackId): Flow<Track?>
    fun albums(): Flow<PagingData<AlbumSummary>>
    /** Snapshot of the most recent [limit] albums, for the Library screen's discography block. */
    suspend fun recentAlbums(limit: Int): List<AlbumSummary>
    fun artists(): Flow<PagingData<Artist>>
    fun album(id: AlbumId): Flow<Album?>
    fun artist(id: ArtistId): Flow<Artist?>
    fun tracksInAlbum(id: AlbumId): Flow<List<Track>>
    suspend fun renameTrack(id: TrackId, title: String)
    suspend fun setTrackCover(id: TrackId, imageUri: String)
    /** New empty album, no tracks yet -- caller adds tracks to it afterwards via [addTrackToAlbum]. */
    suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId
    suspend fun renameAlbum(id: AlbumId, title: String)
    suspend fun setAlbumCover(id: AlbumId, imageUri: String)
    suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean)
    suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId)
    suspend fun removeTrackFromAlbum(trackId: TrackId)
    suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId)
    suspend fun removeTrackFromArtist(trackId: TrackId)
    suspend fun renameArtist(id: ArtistId, name: String)
    suspend fun setArtistPhoto(id: ArtistId, imageUri: String)
    fun tracksByArtist(id: ArtistId): Flow<List<Track>>
    /** Called once a track has actually been "listened to" (see the player module's threshold),
     * not on every skip -- live everywhere that reads Track.playCount via a Flow. */
    suspend fun incrementPlayCount(id: TrackId)
    fun albumsByArtist(id: ArtistId): Flow<List<AlbumSummary>>
    suspend fun import(source: ImportSource): Flow<ImportProgress>
    suspend fun deleteTrack(id: TrackId)
    suspend fun deleteTracks(ids: List<TrackId>)
}

data class ImportProgress(val done: Int, val total: Int)
