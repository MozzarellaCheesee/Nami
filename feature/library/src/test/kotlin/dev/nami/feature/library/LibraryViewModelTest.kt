package dev.nami.feature.library

import androidx.paging.PagingData
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.SearchResult
import dev.nami.domain.TrashRepository
import dev.nami.domain.TrashedPlaylist
import dev.nami.domain.TrashedTrack
import dev.nami.core.model.PlaylistId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private object NoOpTagRepository : dev.nami.domain.TagRepository {
        override fun tags(): Flow<List<dev.nami.domain.Tag>> = flowOf(emptyList())
        override suspend fun createTag(name: String, colorArgb: Int) = error("unused")
        override suspend fun deleteTag(id: dev.nami.domain.TagId) {}
        override fun tagsForTrack(trackId: TrackId): Flow<List<dev.nami.domain.Tag>> = flowOf(emptyList())
        override suspend fun assignTag(trackId: TrackId, tagId: dev.nami.domain.TagId) {}
        override suspend fun unassignTag(trackId: TrackId, tagId: dev.nami.domain.TagId) {}
        override fun tracksForTag(tagId: dev.nami.domain.TagId): Flow<List<Track>> = flowOf(emptyList())
    }

    private val noOpSearchRepo = object : SearchRepository {
        override suspend fun search(query: String) = emptyList<SearchResult>()
        override suspend fun rebuildIndex() {}
    }

    private class FakeTrashRepository : TrashRepository {
        var restoredTrack: TrackId? = null
        override fun trashedTracks(): Flow<List<TrashedTrack>> = flowOf(emptyList())
        override fun trashedPlaylists(): Flow<List<TrashedPlaylist>> = flowOf(emptyList())
        override fun trashedAlbums(): Flow<List<dev.nami.domain.TrashedAlbum>> = flowOf(emptyList())
        override suspend fun restoreTrack(id: TrackId) { restoredTrack = id }
        override suspend fun restorePlaylist(id: PlaylistId) {}
        override suspend fun restoreAlbum(id: AlbumId) {}
        override suspend fun deleteTrackForever(id: TrackId) {}
        override suspend fun deletePlaylistForever(id: PlaylistId) {}
        override suspend fun deleteAlbumForever(id: AlbumId) {}
        override suspend fun purgeExpired() {}
    }

    private class FakePlayerRepository : PlayerRepository {
        var removedTracks: Set<TrackId>? = null
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override val queue: StateFlow<PlayerQueue> = MutableStateFlow(PlayerQueue.EMPTY)
            override val autoAdvanceSignal: StateFlow<Int> = MutableStateFlow(0)
            override val shuffleEnabled: StateFlow<Boolean> = MutableStateFlow(false)
            override val repeatMode: StateFlow<dev.nami.domain.RepeatMode> = MutableStateFlow(dev.nami.domain.RepeatMode.OFF)
        override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {}
        override suspend fun awaitReady() {}
        override suspend fun toggle() {}
        override suspend fun seek(ms: Long) {}
        override suspend fun skipNext() {}
        override suspend fun skipPrevious() {}
        override suspend fun skipToPreviousTrack() {}
        override suspend fun addToQueue(track: PlayableTrack) {}
        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {}
        override suspend fun removeQueueItem(index: Int) {}
        override suspend fun removeTracks(ids: Set<TrackId>) { removedTracks = ids }
        override suspend fun setShuffleEnabled(enabled: Boolean) {}
        override suspend fun setRepeatMode(mode: dev.nami.domain.RepeatMode) {}
            override val activeLoop: StateFlow<dev.nami.domain.LoopRange?> = MutableStateFlow(null)
            override suspend fun setActiveLoop(loop: dev.nami.domain.LoopRange?) {}
        override val sleepTimerRemainingMs: StateFlow<Long?> = MutableStateFlow(null)
        override suspend fun startSleepTimer(durationMs: Long) {}
        override suspend fun cancelSleepTimer() {}
        override suspend fun stop() {}
    }

    @Test
    fun `importing clears progress once finished`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks(): Flow<PagingData<Track>> = flowOf(PagingData.empty())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId): Flow<Track?> = flowOf(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 2), ImportProgress(2, 2))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.importFiles(listOf("content://fake/1", "content://fake/2"))

        assertEquals(null, viewModel.uiState.value.importProgress)
    }

    @Test
    fun `importFolder clears progress once finished`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> {
                assertEquals(ImportSource.Folder("content://tree/fake"), source)
                return flowOf(ImportProgress(1, 3), ImportProgress(3, 3))
            }
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.importFolder("content://tree/fake")

        assertEquals(null, viewModel.uiState.value.importProgress)
    }

    @Test
    fun `selectTab updates uiState selectedTab`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.selectTab(LibraryTab.ALBUMS)

        assertEquals(LibraryTab.ALBUMS, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun `importFiles rebuilds search index after import completes`() = runTest {
        var rebuildCalled = false
        val fakeSearchRepo = object : SearchRepository {
            override suspend fun search(query: String) = emptyList<SearchResult>()
            override suspend fun rebuildIndex() { rebuildCalled = true }
        }
        val fakeRepo = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 1))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, fakeSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.importFiles(listOf("content://fake/1"))

        assertEquals(true, rebuildCalled)
    }

    @Test
    fun `importFiles still rebuilds search index when import throws`() = runTest {
        var rebuildCalled = false
        val fakeSearchRepo = object : SearchRepository {
            override suspend fun search(query: String) = emptyList<SearchResult>()
            override suspend fun rebuildIndex() { rebuildCalled = true }
        }
        val fakeRepo = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flow { throw RuntimeException("boom") }
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, fakeSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.importFiles(listOf("content://fake/1"))

        assertEquals(true, rebuildCalled)
    }

    @Test
    fun `deleteTrack sets lastDeletedTrackIds after repository call`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val fakeTrashRepository = FakeTrashRepository()
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, fakeTrashRepository, FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.deleteTrack(TrackId("t1"))

        assertEquals(setOf(TrackId("t1")), viewModel.uiState.value.lastDeletedTrackIds)
    }

    @Test
    fun `undoLastDelete restores the track and clears state`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val fakeTrashRepository = FakeTrashRepository()
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, fakeTrashRepository, FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)
        viewModel.deleteTrack(TrackId("t1"))

        viewModel.undoLastDelete()

        assertEquals(TrackId("t1"), fakeTrashRepository.restoredTrack)
        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.lastDeletedTrackIds)
    }

    @Test
    fun `toggleTrackSelection adds then removes an id`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)

        viewModel.toggleTrackSelection(TrackId("t1"))
        assertEquals(setOf(TrackId("t1")), viewModel.uiState.value.selectedTrackIds)

        viewModel.toggleTrackSelection(TrackId("t1"))
        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.selectedTrackIds)
    }

    @Test
    fun `deleteSelectedTracks deletes all selected ids and clears selection`() = runTest {
        val deletedIds = mutableListOf<TrackId>()
        val fakeLibraryRepository = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) { deletedIds.addAll(ids) }
        }
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)
        viewModel.toggleTrackSelection(TrackId("t1"))
        viewModel.toggleTrackSelection(TrackId("t2"))

        viewModel.deleteSelectedTracks()

        assertEquals(setOf(TrackId("t1"), TrackId("t2")), deletedIds.toSet())
        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.selectedTrackIds)
        assertEquals(setOf(TrackId("t1"), TrackId("t2")), viewModel.uiState.value.lastDeletedTrackIds)
    }

    @Test
    fun `clearSelection empties selectedTrackIds`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackRating(id: TrackId, rating: Int?) {}
            override suspend fun recordPlayHistory(id: TrackId, playedAt: Long, durationMs: Long) {}
            override suspend fun dailyListeningMinutes(days: Int) = emptyList<dev.nami.domain.DayActivity>()
            override suspend fun listeningSummary(days: Int) = dev.nami.domain.ListeningSummary(0, 0, 0)
            override suspend fun hourOfDayMinutes(days: Int) = List(24) { 0 }
            override suspend fun topTracks(days: Int, limit: Int) = emptyList<dev.nami.domain.TopTrackStat>()
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun incrementSkipCount(id: TrackId) {}
            override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository(), NoOpPlaylistRepository, NoOpSettingsRepository, NoOpTagRepository)
        viewModel.toggleTrackSelection(TrackId("t1"))

        viewModel.clearSelection()

        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.selectedTrackIds)
    }
}

private object NoOpSettingsRepository : dev.nami.domain.SettingsRepository {
    override val autoOpenPlayer = MutableStateFlow(false)
    override fun setAutoOpenPlayer(value: Boolean) {}
    override val hideSystemBars = MutableStateFlow(false)
    override fun setHideSystemBars(value: Boolean) {}
    override val karaokeEnabled = MutableStateFlow(false)
    override fun setKaraokeEnabled(value: Boolean) {}
    override val studyModeEnabled = MutableStateFlow(false)
    override fun setStudyModeEnabled(value: Boolean) {}
    override val lyricsFontPath = MutableStateFlow<String?>(null)
    override fun setLyricsFontPath(path: String?) {}
    override val uiFontPath = MutableStateFlow<String?>(null)
    override fun setUiFontPath(path: String?) {}
    override val doubleTapArtworkAction = MutableStateFlow(dev.nami.domain.GestureAction.NONE)
    override fun setDoubleTapArtworkAction(action: dev.nami.domain.GestureAction) {}
    override val eqEnabled = MutableStateFlow(false)
    override fun setEqEnabled(value: Boolean) {}
    override val eqBandGains = MutableStateFlow(emptyList<Float>())
    override fun setEqBandGains(gainsDb: List<Float>) {}
    override val bitPerfectUsbEnabled = MutableStateFlow(false)
    override fun setBitPerfectUsbEnabled(value: Boolean) {}
    override val replayGainEnabled = MutableStateFlow(false)
    override fun setReplayGainEnabled(value: Boolean) {}
    override val ditherEnabled = MutableStateFlow(false)
    override fun setDitherEnabled(value: Boolean) {}
    override val crossfadeEnabled = MutableStateFlow(false)
    override fun setCrossfadeEnabled(value: Boolean) {}
    override val smartCrossfadeEnabled = MutableStateFlow(false)
    override fun setSmartCrossfadeEnabled(value: Boolean) {}
    override val playbackGainDb = MutableStateFlow(0f)
    override fun setPlaybackGainDb(value: Float) {}
    override val hiFiEnabled = MutableStateFlow(false)
    override fun setHiFiEnabled(value: Boolean) {}
    override val nightModeEnabled = MutableStateFlow(false)
    override fun setNightModeEnabled(value: Boolean) {}
    override val amoledEnabled = MutableStateFlow(false)
    override fun setAmoledEnabled(value: Boolean) {}
    override val outputProfilesEnabled = MutableStateFlow(false)
    override fun setOutputProfilesEnabled(value: Boolean) {}
    override val outputProfiles = MutableStateFlow(emptyMap<dev.nami.domain.OutputDeviceType, dev.nami.domain.OutputProfile>())
    override fun setOutputProfile(type: dev.nami.domain.OutputDeviceType, profile: dev.nami.domain.OutputProfile) {}
    override val stands4Uid = MutableStateFlow("")
    override fun setStands4Uid(value: String) {}
    override val stands4Token = MutableStateFlow("")
    override fun setStands4Token(value: String) {}
    override val stands4RequestsToday = MutableStateFlow(0)
    override fun recordStands4Request() {}
    override val lastPlaybackQueueTrackIds = MutableStateFlow(emptyList<String>())
    override val lastPlaybackQueueIndex = MutableStateFlow(0)
    override val lastPlaybackPositionMs = MutableStateFlow(0L)
    override val lastPlaybackPausedAt = MutableStateFlow(0L)
    override fun setLastPlayback(queueTrackIds: List<String>, queueIndex: Int, positionMs: Long, pausedAt: Long) {}
    override val watchedFolders = MutableStateFlow(emptyList<String>())
    override fun addWatchedFolder(treeUri: String) {}
    override fun removeWatchedFolder(treeUri: String) {}
    override val deeplApiKey = MutableStateFlow("")
    override fun setDeeplApiKey(value: String) {}
    override val shuffleMode = MutableStateFlow(dev.nami.domain.ShuffleMode.TRUE_RANDOM)
    override fun setShuffleMode(mode: dev.nami.domain.ShuffleMode) {}
    override val sessions = MutableStateFlow(emptyList<dev.nami.domain.Session>())
    override fun saveSession(session: dev.nami.domain.Session) {}
    override fun deleteSession(name: String) {}
    override val lastAppliedSessionName = MutableStateFlow<String?>(null)
    override fun setLastAppliedSessionName(name: String?) {}
    override val scrobblingEnabled = MutableStateFlow(false)
    override fun setScrobblingEnabled(value: Boolean) {}
    override val airPlayEnabled = MutableStateFlow(false)
    override fun setAirPlayEnabled(value: Boolean) {}
    override val yandexStationEnabled = MutableStateFlow(false)
    override fun setYandexStationEnabled(value: Boolean) {}
    override val yandexOAuthToken = MutableStateFlow<String?>(null)
    override fun setYandexOAuthToken(token: String?) {}
    override val yandexClientId = MutableStateFlow<String?>(null)
    override fun setYandexClientId(value: String?) {}
    override val jamendoClientId = MutableStateFlow<String?>(null)
    override fun setJamendoClientId(value: String?) {}
    override val soundCloudClientId = MutableStateFlow<String?>(null)
    override fun setSoundCloudClientId(value: String?) {}
    override val listenBrainzToken = MutableStateFlow<String?>(null)
    override fun setListenBrainzToken(token: String?) {}
    override val homeBlocks = MutableStateFlow(dev.nami.domain.DEFAULT_HOME_BLOCKS)
    override fun setHomeBlocks(blocks: List<dev.nami.domain.HomeBlockConfig>) {}
    override val nowPlayingShowTechInfo = MutableStateFlow(true)
    override fun setNowPlayingShowTechInfo(value: Boolean) {}
    override val nowPlayingShowShuffle = MutableStateFlow(true)
    override fun setNowPlayingShowShuffle(value: Boolean) {}
    override val nowPlayingShowRepeat = MutableStateFlow(true)
    override fun setNowPlayingShowRepeat(value: Boolean) {}
    override val nowPlayingBlockOrder = MutableStateFlow(dev.nami.domain.DEFAULT_NOW_PLAYING_BLOCKS)
    override fun setNowPlayingBlockOrder(order: List<dev.nami.domain.NowPlayingBlock>) {}
    override val nowPlayingCompactCover = MutableStateFlow(false)
    override fun setNowPlayingCompactCover(value: Boolean) {}
    override val nowPlayingLineProgress = MutableStateFlow(false)
    override fun setNowPlayingLineProgress(value: Boolean) {}
    override val themeColorOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun setThemeColorOverride(token: String, hex: String?) {}
    override fun resetThemeColors() {}
    override val themeShapeOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    override fun setThemeShapeOverride(token: String, dp: Int?) {}
    override val themeDensityScale = MutableStateFlow(1f)
    override fun setThemeDensityScale(value: Float) {}
    override val themeFontScale = MutableStateFlow(1f)
    override fun setThemeFontScale(value: Float) {}
    override val blurEnabled = MutableStateFlow(true)
    override fun setBlurEnabled(value: Boolean) {}
    override val autoNightAmoled = MutableStateFlow(false)
    override fun setAutoNightAmoled(value: Boolean) {}
    override fun resetThemeShapeAndDensity() {}
    override val bottomTabs = MutableStateFlow(dev.nami.domain.DEFAULT_BOTTOM_TABS)
    override fun setBottomTabs(tabs: List<dev.nami.domain.BottomTabConfig>) {}
    override val bottomTabLabelsHidden = MutableStateFlow(false)
    override fun setBottomTabLabelsHidden(value: Boolean) {}
    override val miniPlayerSideSwipeAction = MutableStateFlow(dev.nami.domain.GestureAction.SKIP_NEXT)
    override fun setMiniPlayerSideSwipeAction(action: dev.nami.domain.GestureAction) {}
    override val nowPlayingLayoutPreset = MutableStateFlow(dev.nami.domain.NowPlayingLayoutPreset.CUSTOM)
    override fun setNowPlayingLayoutPreset(preset: dev.nami.domain.NowPlayingLayoutPreset) {}
    override val crossfeedEnabled = MutableStateFlow(false)
    override fun setCrossfeedEnabled(value: Boolean) {}
    override val deviceAudioProfile = MutableStateFlow<String?>(null)
    override fun setDeviceAudioProfile(value: String?) {}
    override val convolutionEnabled = MutableStateFlow(false)
    override fun setConvolutionEnabled(value: Boolean) {}
    override val convolutionIrPath = MutableStateFlow<String?>(null)
    override fun setConvolutionIrPath(value: String?) {}
}

private object NoOpPlaylistRepository : PlaylistRepository {
    override fun playlists() = flowOf(PagingData.empty<dev.nami.core.model.PlaylistSummary>())
    override suspend fun recentPlaylists(limit: Int) = emptyList<dev.nami.core.model.PlaylistSummary>()
    override fun playlist(id: PlaylistId) = flowOf<dev.nami.core.model.Playlist?>(null)
    override fun tracksInPlaylist(id: PlaylistId) = flowOf(emptyList<Track>())
    override suspend fun createPlaylist(name: String): PlaylistId = error("unused")
    override suspend fun renamePlaylist(id: PlaylistId, name: String) = error("unused")
    override suspend fun deletePlaylist(id: PlaylistId) = error("unused")
    override suspend fun setCoverImage(id: PlaylistId, imageUri: String) = error("unused")
    override suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId) = error("unused")
    override suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId) = error("unused")
    override suspend fun exportM3u8(id: PlaylistId, destinationUri: String) = error("unused")
    override suspend fun importM3u8(sourceUri: String, playlistName: String) = error("unused")
    override fun isTrackLiked(trackId: TrackId) = flowOf(false)
    override suspend fun toggleLike(trackId: TrackId): Boolean = error("unused")
    override suspend fun likeTrack(trackId: TrackId) {}
    override suspend fun createSmartPlaylist(name: String, query: dev.nami.domain.SmartQuery) = error("unused")
    override suspend fun updateSmartQuery(id: dev.nami.core.model.PlaylistId, query: dev.nami.domain.SmartQuery) {}
    override suspend fun setPlaybackSettings(id: dev.nami.core.model.PlaylistId, eqGainsCsv: String?, crossfadeEnabled: Boolean?, shuffleOnStart: Boolean?) {}
    override fun parseSmartQuery(json: String): dev.nami.domain.SmartQuery? = null
}
