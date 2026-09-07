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
    /** См. LibraryHealthReport -- пробегает по всей библиотеке, не для частого вызова. */
    suspend fun libraryHealthReport(): LibraryHealthReport

    fun tracks(): Flow<PagingData<Track>>
    /** Snapshot of every non-deleted track, same order as [tracks], for building a full playback queue. */
    suspend fun allTracksOrdered(): List<Track>
    fun track(id: TrackId): Flow<Track?>
    fun albums(): Flow<PagingData<AlbumSummary>>
    /** Live -- reflects renames, cover/artist changes and album/track add-or-remove without the
     * Library screen needing to be reopened -- most recent [limit] albums, for the discography block. */
    fun recentAlbums(limit: Int): Flow<List<AlbumSummary>>
    /** Same shape as [recentAlbums] but for the Tracks tab's "Артисты" preview row. */
    fun featuredArtists(limit: Int): Flow<List<Artist>>
    fun artists(): Flow<PagingData<Artist>>
    fun album(id: AlbumId): Flow<Album?>
    fun artist(id: ArtistId): Flow<Artist?>
    fun tracksInAlbum(id: AlbumId): Flow<List<Track>>
    suspend fun renameTrack(id: TrackId, title: String)
    suspend fun setTrackCover(id: TrackId, imageUri: String)
    /** New empty album, no tracks yet -- caller adds tracks to it afterwards via [addTrackToAlbum]. */
    suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId

    /** Soft-deletes the album itself (not just a best-effort loop over its tracks) -- moves to
     * trash for 30 days, restorable via TrashRepository.restoreAlbum. Also trashes every track in
     * the album. */
    suspend fun deleteAlbum(id: AlbumId)
    suspend fun renameAlbum(id: AlbumId, title: String)
    suspend fun setAlbumCover(id: AlbumId, imageUri: String)
    suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean)
    suspend fun setAlbumYear(id: AlbumId, year: Int?)
    /** Replaces the album's whole artist-credit list with just this one artist (or clears it). */
    suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?)
    /** Every artist credited on the album -- can be more than one (compilations, splits, features). */
    fun albumArtists(id: AlbumId): Flow<List<Artist>>
    suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId)
    suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId)
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
    suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long)
    suspend fun dailyListeningMinutes(days: Int): List<DayActivity>
    suspend fun setTrackReplayGain(id: TrackId, gainDb: Float)

    /** План.md §22.17 "Заметки к треку" -- free-text personal comment, null clears it. */
    suspend fun setTrackNote(id: TrackId, note: String?)

    /** П.md §23.20 "Встроенный редактор тегов с батч-режимом" -- applies whichever of
     * [artistName]/[albumName]/[year]/[genre] is non-null to every track in [ids]. Artist/album
     * resolve through the same MetadataResolver import already uses (find-or-create by name, so
     * batch-editing 50 tracks to "Farewell225" doesn't create 50 new Artist rows). [year] with no
     * [albumName] applies to each track's EXISTING album (if it has one) rather than creating one. */
    suspend fun batchEditTracks(ids: List<TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?)

    /** П.md §23.20 "автозаполнение из MusicBrainz". */
    suspend fun searchMusicBrainz(title: String, artistName: String?): List<MusicBrainzCandidate>

    /** См. TrackEntity.skipCount -- "правила автоочереди" (План.md §22.13). */
    suspend fun incrementSkipCount(id: TrackId)

    /** См. BpmKeyAnalyzer -- caches its result on the track. */
    suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?)
    fun albumsByArtist(id: ArtistId): Flow<List<AlbumSummary>>
    suspend fun import(source: ImportSource): Flow<ImportProgress>
    suspend fun deleteTrack(id: TrackId)
    suspend fun deleteTracks(ids: List<TrackId>)
}

data class ImportProgress(val done: Int, val total: Int)
