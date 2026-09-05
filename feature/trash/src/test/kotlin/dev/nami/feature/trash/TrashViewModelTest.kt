package dev.nami.feature.trash

import dev.nami.core.model.PlaylistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.TrashRepository
import dev.nami.domain.TrashedPlaylist
import dev.nami.domain.TrashedTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

class TrashViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { kotlinx.coroutines.Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain() }

    private class FakeTrashRepository : TrashRepository {
        val tracksFlow = MutableStateFlow<List<TrashedTrack>>(emptyList())
        val playlistsFlow = MutableStateFlow<List<TrashedPlaylist>>(emptyList())
        var restoredTrack: TrackId? = null
        var deletedForeverTrack: TrackId? = null

        override fun trashedTracks() = tracksFlow
        override fun trashedPlaylists() = playlistsFlow
        override suspend fun restoreTrack(id: TrackId) { restoredTrack = id }
        override suspend fun restorePlaylist(id: PlaylistId) {}
        override suspend fun deleteTrackForever(id: TrackId) { deletedForeverTrack = id }
        override suspend fun deletePlaylistForever(id: PlaylistId) {}
        override suspend fun purgeExpired() {}
    }

    @Test
    fun `uiState combines trashed tracks and playlists`() = runTest {
        val repository = FakeTrashRepository()
        val viewModel = TrashViewModel(repository)

        val track = TrashedTrack(
            track = Track(
                id = TrackId("t1"), title = "Song", artistId = null, albumId = null,
                durationMs = 1000, path = "/trash/t1.flac", format = "flac", sizeBytes = 100, dateAdded = 1000,
            ),
            deletedAt = 5000,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        repository.tracksFlow.value = listOf(track)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(track), viewModel.uiState.value.tracks)
    }

    @Test
    fun `restoreTrack delegates to repository`() = runTest {
        val repository = FakeTrashRepository()
        val viewModel = TrashViewModel(repository)

        viewModel.restoreTrack(TrackId("t1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(TrackId("t1"), repository.restoredTrack)
    }
}
