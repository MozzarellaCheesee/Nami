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

    private val noOpSearchRepo = object : SearchRepository {
        override suspend fun search(query: String) = emptyList<SearchResult>()
        override suspend fun rebuildIndex() {}
    }

    private class FakeTrashRepository : TrashRepository {
        var restoredTrack: TrackId? = null
        override fun trashedTracks(): Flow<List<TrashedTrack>> = flowOf(emptyList())
        override fun trashedPlaylists(): Flow<List<TrashedPlaylist>> = flowOf(emptyList())
        override suspend fun restoreTrack(id: TrackId) { restoredTrack = id }
        override suspend fun restorePlaylist(id: PlaylistId) {}
        override suspend fun deleteTrackForever(id: TrackId) {}
        override suspend fun deletePlaylistForever(id: PlaylistId) {}
        override suspend fun purgeExpired() {}
    }

    private class FakePlayerRepository : PlayerRepository {
        var removedTracks: Set<TrackId>? = null
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override val queue: StateFlow<PlayerQueue> = MutableStateFlow(PlayerQueue.EMPTY)
        override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {}
        override suspend fun toggle() {}
        override suspend fun seek(ms: Long) {}
        override suspend fun skipNext() {}
        override suspend fun skipPrevious() {}
        override suspend fun skipToPreviousTrack() {}
        override suspend fun addToQueue(track: PlayableTrack) {}
        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {}
        override suspend fun removeQueueItem(index: Int) {}
        override suspend fun removeTracks(ids: Set<TrackId>) { removedTracks = ids }
        override suspend fun stop() {}
    }

    @Test
    fun `importing clears progress once finished`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override fun tracks(): Flow<PagingData<Track>> = flowOf(PagingData.empty())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId): Flow<Track?> = flowOf(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 2), ImportProgress(2, 2))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository())

        viewModel.importFiles(listOf("content://fake/1", "content://fake/2"))

        assertEquals(null, viewModel.uiState.value.importProgress)
    }

    @Test
    fun `importFolder clears progress once finished`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> {
                assertEquals(ImportSource.Folder("content://tree/fake"), source)
                return flowOf(ImportProgress(1, 3), ImportProgress(3, 3))
            }
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository())

        viewModel.importFolder("content://tree/fake")

        assertEquals(null, viewModel.uiState.value.importProgress)
    }

    @Test
    fun `selectTab updates uiState selectedTab`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository())

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
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 1))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, fakeSearchRepo, FakeTrashRepository(), FakePlayerRepository())

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
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flow { throw RuntimeException("boom") }
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeRepo, fakeSearchRepo, FakeTrashRepository(), FakePlayerRepository())

        viewModel.importFiles(listOf("content://fake/1"))

        assertEquals(true, rebuildCalled)
    }

    @Test
    fun `deleteTrack sets lastDeletedTrackIds after repository call`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val fakeTrashRepository = FakeTrashRepository()
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, fakeTrashRepository, FakePlayerRepository())

        viewModel.deleteTrack(TrackId("t1"))

        assertEquals(setOf(TrackId("t1")), viewModel.uiState.value.lastDeletedTrackIds)
    }

    @Test
    fun `undoLastDelete restores the track and clears state`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val fakeTrashRepository = FakeTrashRepository()
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, fakeTrashRepository, FakePlayerRepository())
        viewModel.deleteTrack(TrackId("t1"))

        viewModel.undoLastDelete()

        assertEquals(TrackId("t1"), fakeTrashRepository.restoredTrack)
        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.lastDeletedTrackIds)
    }

    @Test
    fun `toggleTrackSelection adds then removes an id`() = runTest {
        val fakeLibraryRepository = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository())

        viewModel.toggleTrackSelection(TrackId("t1"))
        assertEquals(setOf(TrackId("t1")), viewModel.uiState.value.selectedTrackIds)

        viewModel.toggleTrackSelection(TrackId("t1"))
        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.selectedTrackIds)
    }

    @Test
    fun `deleteSelectedTracks deletes all selected ids and clears selection`() = runTest {
        val deletedIds = mutableListOf<TrackId>()
        val fakeLibraryRepository = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) { deletedIds.addAll(ids) }
        }
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository())
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
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override suspend fun recentAlbums(limit: Int): List<AlbumSummary> = emptyList()
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
            override suspend fun deleteTrack(id: TrackId) {}
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val viewModel = LibraryViewModel(fakeLibraryRepository, noOpSearchRepo, FakeTrashRepository(), FakePlayerRepository())
        viewModel.toggleTrackSelection(TrackId("t1"))

        viewModel.clearSelection()

        assertEquals(emptySet<TrackId>(), viewModel.uiState.value.selectedTrackIds)
    }
}
