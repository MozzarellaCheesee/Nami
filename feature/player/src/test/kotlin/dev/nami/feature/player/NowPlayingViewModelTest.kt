package dev.nami.feature.player

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class NowPlayingViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `playTrack resolves path from library and starts playback with it`() = runTest {
        val track = Track(
            id = TrackId("t1"), title = "Window View", artistId = null, albumId = null,
            durationMs = 180_000, path = "/data/music/real-file.flac",
            format = "flac", sizeBytes = 1, dateAdded = 0,
        )
        var playedQueue: List<TrackId>? = null

        val playerRepo = object : PlayerRepository {
            override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
            override suspend fun play(queue: List<TrackId>, startIndex: Int, startMs: Long) {
                playedQueue = queue
            }
            override suspend fun toggle() {}
            override suspend fun seek(ms: Long) {}
            override suspend fun skipNext() {}
            override suspend fun skipPrevious() {}
        }
        val libraryRepo = object : LibraryRepository {
            override fun tracks() = throw NotImplementedError()
            override fun track(id: TrackId): Flow<Track?> = flowOf(track)
            override suspend fun import(source: ImportSource) = throw NotImplementedError()
        }

        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)
        viewModel.playTrack(TrackId("t1"))

        assertEquals(listOf(TrackId("/data/music/real-file.flac")), playedQueue)
    }
}
