package dev.nami.feature.player

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerQueue
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

    private fun trackFixture(id: String, path: String) = Track(
        id = TrackId(id), title = "Window View", artistId = null, albumId = null,
        durationMs = 180_000, path = path, format = "flac", sizeBytes = 1, dateAdded = 0,
    )

    private class RecordingPlayerRepository : PlayerRepository {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override val queue: StateFlow<PlayerQueue> = MutableStateFlow(PlayerQueue.EMPTY)
        var playedTracks: List<PlayableTrack>? = null
        var addedTrack: PlayableTrack? = null
        var movedFromTo: Pair<Int, Int>? = null
        var removedIndex: Int? = null

        override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {
            playedTracks = tracks
        }
        override suspend fun toggle() {}
        override suspend fun seek(ms: Long) {}
        override suspend fun skipNext() {}
        override suspend fun skipPrevious() {}
        override suspend fun addToQueue(track: PlayableTrack) {
            addedTrack = track
        }
        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
            movedFromTo = fromIndex to toIndex
        }
        override suspend fun removeQueueItem(index: Int) {
            removedIndex = index
        }
        override suspend fun removeTracks(ids: Set<TrackId>) {}
    }

    private val libraryRepo = object : LibraryRepository {
        override fun tracks() = throw NotImplementedError()
        override fun track(id: TrackId) = flowOf(trackFixture("t1", "/data/music/real-file.flac"))
        override fun albums() = throw NotImplementedError()
        override fun artists() = throw NotImplementedError()
        override fun album(id: dev.nami.core.model.AlbumId) = throw NotImplementedError()
        override fun artist(id: dev.nami.core.model.ArtistId) = throw NotImplementedError()
        override fun tracksInAlbum(id: dev.nami.core.model.AlbumId) = throw NotImplementedError()
        override fun tracksByArtist(id: dev.nami.core.model.ArtistId) = throw NotImplementedError()
        override fun albumsByArtist(id: dev.nami.core.model.ArtistId) = throw NotImplementedError()
        override suspend fun import(source: ImportSource): Flow<dev.nami.domain.ImportProgress> = throw NotImplementedError()
        override suspend fun deleteTrack(id: TrackId) = throw NotImplementedError()
        override suspend fun deleteTracks(ids: List<TrackId>) {}
    }

    @Test
    fun `playTrack resolves path from library and starts playback with it`() {
        val playerRepo = RecordingPlayerRepository()
        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)

        viewModel.playTrack(TrackId("t1"))

        assertEquals(
            listOf(PlayableTrack(TrackId("t1"), "Window View", null, "/data/music/real-file.flac")),
            playerRepo.playedTracks,
        )
    }

    @Test
    fun `playTracks maps a whole list with the given artist name and start index`() {
        val playerRepo = RecordingPlayerRepository()
        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)
        val tracks = listOf(trackFixture("t1", "/data/music/a.flac"), trackFixture("t2", "/data/music/b.flac"))

        viewModel.playTracks(tracks, artistName = "Farewell225", startIndex = 1)

        assertEquals(
            listOf(
                PlayableTrack(TrackId("t1"), "Window View", "Farewell225", "/data/music/a.flac"),
                PlayableTrack(TrackId("t2"), "Window View", "Farewell225", "/data/music/b.flac"),
            ),
            playerRepo.playedTracks,
        )
    }

    @Test
    fun `addToQueue forwards a mapped PlayableTrack`() {
        val playerRepo = RecordingPlayerRepository()
        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)

        viewModel.addToQueue(trackFixture("t1", "/data/music/a.flac"), artistName = "Farewell225")

        assertEquals(PlayableTrack(TrackId("t1"), "Window View", "Farewell225", "/data/music/a.flac"), playerRepo.addedTrack)
    }

    @Test
    fun `moveQueueItem and removeQueueItem delegate to the repository`() {
        val playerRepo = RecordingPlayerRepository()
        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)

        viewModel.moveQueueItem(0, 2)
        viewModel.removeQueueItem(1)

        assertEquals(0 to 2, playerRepo.movedFromTo)
        assertEquals(1, playerRepo.removedIndex)
    }
}
