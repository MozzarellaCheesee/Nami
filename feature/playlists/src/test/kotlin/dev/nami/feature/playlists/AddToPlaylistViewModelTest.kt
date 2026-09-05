package dev.nami.feature.playlists

import androidx.paging.PagingData
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportM3u8Result
import dev.nami.domain.PlaylistRepository
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
class AddToPlaylistViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingPlaylistRepository : PlaylistRepository {
        val addedTo = mutableListOf<Pair<PlaylistId, TrackId>>()
        var createdName: String? = null
        override fun playlists() = flowOf(PagingData.empty<PlaylistSummary>())
        override fun playlist(id: PlaylistId) = flowOf<Playlist?>(null)
        override fun tracksInPlaylist(id: PlaylistId) = flowOf(emptyList<Track>())
        override suspend fun createPlaylist(name: String): PlaylistId {
            createdName = name
            return PlaylistId("new-id")
        }
        override suspend fun renamePlaylist(id: PlaylistId, name: String) {}
        override suspend fun deletePlaylist(id: PlaylistId) {}
        override suspend fun setCoverImage(id: PlaylistId, imageUri: String) {}
        override suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId) {
            addedTo.add(playlistId to trackId)
        }
        override suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId) {}
        override suspend fun exportM3u8(id: PlaylistId, destinationUri: String) {}
        override suspend fun importM3u8(sourceUri: String, playlistName: String) =
            ImportM3u8Result(PlaylistId("x"), 0, 0)
    }

    @Test
    fun `addToExistingPlaylist calls repository addTrack for a single id`() = runTest {
        val repo = RecordingPlaylistRepository()
        val viewModel = AddToPlaylistViewModel(repo)

        viewModel.addToExistingPlaylist(PlaylistId("p1"), setOf(TrackId("t1")))

        assertEquals(listOf(PlaylistId("p1") to TrackId("t1")), repo.addedTo)
    }

    @Test
    fun `addToExistingPlaylist calls repository addTrack for every id in the set`() = runTest {
        val repo = RecordingPlaylistRepository()
        val viewModel = AddToPlaylistViewModel(repo)

        viewModel.addToExistingPlaylist(PlaylistId("p1"), setOf(TrackId("t1"), TrackId("t2")))

        assertEquals(
            setOf(PlaylistId("p1") to TrackId("t1"), PlaylistId("p1") to TrackId("t2")),
            repo.addedTo.toSet(),
        )
    }

    @Test
    fun `addToNewPlaylist creates then adds every selected track`() = runTest {
        val repo = RecordingPlaylistRepository()
        val viewModel = AddToPlaylistViewModel(repo)

        viewModel.addToNewPlaylist("Doujin", setOf(TrackId("t1"), TrackId("t2")))

        assertEquals("Doujin", repo.createdName)
        assertEquals(
            setOf(PlaylistId("new-id") to TrackId("t1"), PlaylistId("new-id") to TrackId("t2")),
            repo.addedTo.toSet(),
        )
    }
}
