package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AlbumDetailViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads album and its tracks for the given id`() = runTest {
        val album = Album(id = AlbumId("al1"), title = "Doujin Compilation", artistId = null, year = 2023, artworkPath = null)
        val track = Track(
            id = TrackId("t1"), title = "Window View", artistId = null, albumId = null,
            durationMs = 180_000, path = "/music/t1.flac", format = "flac", sizeBytes = 1, dateAdded = 0,
        )
        val fakeRepo = object : LibraryRepository {
            override fun tracks() = throw NotImplementedError()
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
            override fun track(id: TrackId) = throw NotImplementedError()
            override fun albums() = throw NotImplementedError()
            override fun artists() = throw NotImplementedError()
            override fun album(id: AlbumId) = flowOf(album)
            override fun artist(id: ArtistId) = throw NotImplementedError()
            override fun tracksInAlbum(id: AlbumId) = flowOf(listOf(track))
            override fun tracksByArtist(id: ArtistId) = throw NotImplementedError()
            override fun albumsByArtist(id: ArtistId) = throw NotImplementedError()
            override suspend fun import(source: ImportSource) = throw NotImplementedError()
            override suspend fun deleteTrack(id: TrackId) = throw NotImplementedError()
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val savedStateHandle = SavedStateHandle(mapOf("albumId" to "al1"))

        val viewModel = AlbumDetailViewModel(fakeRepo, savedStateHandle)

        assertEquals(album, viewModel.uiState.value.album)
        assertEquals(listOf(track), viewModel.uiState.value.tracks)
    }
}
