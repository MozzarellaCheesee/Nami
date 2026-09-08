package dev.nami.feature.playlists

import androidx.lifecycle.SavedStateHandle
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
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
class PlaylistDetailViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun trackFixture(id: String) = Track(
        id = TrackId(id), title = id, artistId = null, albumId = null,
        durationMs = 1000, path = "/music/$id.flac", format = "flac", sizeBytes = 1, dateAdded = 0,
    )

    @Test
    fun `loads playlist and its tracks for the given id`() = runTest {
        val playlist = Playlist(id = PlaylistId("p1"), name = "Doujin", coverPath = null)
        val track = trackFixture("t1")
        var removedTrackId: TrackId? = null
        val repo = object : PlaylistRepository {
            override fun playlists() = throw NotImplementedError()
            override suspend fun recentPlaylists(limit: Int) = emptyList<dev.nami.core.model.PlaylistSummary>()
            override fun playlist(id: PlaylistId) = flowOf(playlist)
            override fun tracksInPlaylist(id: PlaylistId) = flowOf(listOf(track))
            override suspend fun createPlaylist(name: String) = throw NotImplementedError()
            override suspend fun renamePlaylist(id: PlaylistId, name: String) {}
            override suspend fun deletePlaylist(id: PlaylistId) {}
            override suspend fun setCoverImage(id: PlaylistId, imageUri: String) {}
            override suspend fun addTrack(playlistId: PlaylistId, trackId: TrackId) {}
            override suspend fun removeTrack(playlistId: PlaylistId, trackId: TrackId) {
                removedTrackId = trackId
            }
            override suspend fun exportM3u8(id: PlaylistId, destinationUri: String) {}
            override suspend fun importM3u8(sourceUri: String, playlistName: String) =
                ImportM3u8Result(PlaylistId("x"), 0, 0)
            override fun isTrackLiked(trackId: TrackId) = flowOf(false)
            override suspend fun toggleLike(trackId: TrackId) = true
            override suspend fun likeTrack(trackId: TrackId) {}
            override suspend fun createSmartPlaylist(name: String, query: dev.nami.domain.SmartQuery) = error("unused")
            override suspend fun updateSmartQuery(id: PlaylistId, query: dev.nami.domain.SmartQuery) {}
            override fun parseSmartQuery(json: String): dev.nami.domain.SmartQuery? = null
        }
        val savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p1"))

        val viewModel = PlaylistDetailViewModel(repo, savedStateHandle)

        assertEquals(playlist, viewModel.uiState.value.playlist)
        assertEquals(listOf(track), viewModel.uiState.value.tracks)

        viewModel.removeTrack(TrackId("t1"))
        assertEquals(TrackId("t1"), removedTrackId)
    }
}
