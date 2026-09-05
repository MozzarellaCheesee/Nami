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
import dev.nami.domain.SearchRepository
import dev.nami.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

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

    @Test
    fun `importing emits progress then reaches total`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override fun tracks(): Flow<PagingData<Track>> = flowOf(PagingData.empty())
            override fun track(id: TrackId): Flow<Track?> = flowOf(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 2), ImportProgress(2, 2))
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo)

        viewModel.importFiles(listOf("content://fake/1", "content://fake/2"))

        assertEquals(ImportProgress(2, 2), viewModel.uiState.value.importProgress)
    }

    @Test
    fun `selectTab updates uiState selectedTab`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override fun tracks() = flowOf(PagingData.empty<Track>())
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun import(source: ImportSource) = flowOf(ImportProgress(0, 0))
        }
        val viewModel = LibraryViewModel(fakeRepo, noOpSearchRepo)

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
            override fun track(id: TrackId) = flowOf<Track?>(null)
            override fun albums() = flowOf(PagingData.empty<AlbumSummary>())
            override fun artists() = flowOf(PagingData.empty<Artist>())
            override fun album(id: AlbumId) = flowOf<Album?>(null)
            override fun artist(id: ArtistId) = flowOf<Artist?>(null)
            override fun tracksInAlbum(id: AlbumId) = flowOf(emptyList<Track>())
            override fun tracksByArtist(id: ArtistId) = flowOf(emptyList<Track>())
            override fun albumsByArtist(id: ArtistId) = flowOf(emptyList<AlbumSummary>())
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 1))
        }
        val viewModel = LibraryViewModel(fakeRepo, fakeSearchRepo)

        viewModel.importFiles(listOf("content://fake/1"))

        assertEquals(true, rebuildCalled)
    }
}
