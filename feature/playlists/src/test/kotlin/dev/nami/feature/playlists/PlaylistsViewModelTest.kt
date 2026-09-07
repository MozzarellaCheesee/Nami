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
class PlaylistsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingPlaylistRepository : PlaylistRepository {
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
        override suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId) {}
        override suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId) {}
        override suspend fun exportM3u8(id: PlaylistId, destinationUri: String) {}
        override suspend fun importM3u8(sourceUri: String, playlistName: String) =
            ImportM3u8Result(PlaylistId("x"), 0, 0)
        override fun isTrackLiked(trackId: TrackId) = kotlinx.coroutines.flow.flowOf(false)
        override suspend fun toggleLike(trackId: TrackId) = true
        override suspend fun likeTrack(trackId: TrackId) {}
        override suspend fun createSmartPlaylist(name: String, query: dev.nami.domain.SmartQuery) = error("unused")
        override suspend fun updateSmartQuery(id: PlaylistId, query: dev.nami.domain.SmartQuery) {}
        override fun parseSmartQuery(json: String): dev.nami.domain.SmartQuery? = null
    }

    @Test
    fun `createPlaylist forwards the name to the repository`() = runTest {
        val repo = RecordingPlaylistRepository()
        val viewModel = PlaylistsViewModel(repo)

        viewModel.createPlaylist("Doujin")

        assertEquals("Doujin", repo.createdName)
    }
}
