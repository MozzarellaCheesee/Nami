package dev.nami.feature.search

import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Album
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // Only recentAlbums/featuredArtists are ever read by SearchViewModel -- everything else here
    // errors loudly if a test path ever ends up calling it, instead of silently returning junk.
    private val fakeLibraryRepository = object : LibraryRepository {
        override suspend fun libraryHealthReport() = error("unused")
            override suspend fun batchEditTracks(ids: List<dev.nami.core.model.TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) = error("unused")
            override suspend fun searchMusicBrainz(title: String, artistName: String?) = error("unused")
        override fun tracks() = error("unused")
        override suspend fun allTracksOrdered(): List<Track> = error("unused")
        override fun track(id: TrackId) = error("unused")
        override fun albums() = error("unused")
        override fun recentAlbums(limit: Int): Flow<List<AlbumSummary>> = flowOf(emptyList())
        override fun featuredArtists(limit: Int): Flow<List<Artist>> = flowOf(emptyList())
        override fun artists() = error("unused")
        override fun album(id: AlbumId): Flow<Album?> = error("unused")
        override fun artist(id: ArtistId): Flow<Artist?> = error("unused")
        override fun tracksInAlbum(id: AlbumId): Flow<List<Track>> = error("unused")
        override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
        override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
        override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
        override suspend fun deleteAlbum(id: AlbumId) = error("unused")
        override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
        override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
        override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
        override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
        override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
        override fun albumArtists(id: AlbumId): Flow<List<Artist>> = error("unused")
        override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
        override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
        override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
        override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
        override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
        override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
        override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
        override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
        override fun tracksByArtist(id: ArtistId): Flow<List<Track>> = error("unused")
        override suspend fun incrementPlayCount(id: TrackId) = error("unused")
        override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) = error("unused")
        override suspend fun setTrackNote(id: TrackId, note: String?) = error("unused")
        override suspend fun incrementSkipCount(id: TrackId) = error("unused")
        override suspend fun setTrackBpmKey(id: TrackId, bpm: Float?, musicalKey: String?) = error("unused")
        override fun albumsByArtist(id: ArtistId): Flow<List<AlbumSummary>> = error("unused")
        override suspend fun import(source: ImportSource): Flow<ImportProgress> = error("unused")
        override suspend fun deleteTrack(id: TrackId) = error("unused")
        override suspend fun deleteTracks(ids: List<TrackId>) = error("unused")
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `updating query debounces then emits results`() = runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): List<SearchResult> =
                listOf(SearchResult.TrackResult(TrackId("t1"), "Window View", "Farewell225", null))
            override suspend fun rebuildIndex() {}
        }
        val viewModel = SearchViewModel(fakeRepo, fakeLibraryRepository)

        viewModel.onQueryChange("window")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.results.size)
        assertTrue(viewModel.uiState.value.results[0] is SearchResult.TrackResult)
    }

    @Test
    fun `blank query clears results without calling search`() = runTest {
        var searchCalled = false
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): List<SearchResult> {
                searchCalled = true
                return emptyList()
            }
            override suspend fun rebuildIndex() {}
        }
        val viewModel = SearchViewModel(fakeRepo, fakeLibraryRepository)

        viewModel.onQueryChange("")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.results.size)
        assertTrue(!searchCalled)
    }
}
