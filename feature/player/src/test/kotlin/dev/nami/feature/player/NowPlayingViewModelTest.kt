package dev.nami.feature.player

import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.ArtistId
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
            override val autoAdvanceSignal: StateFlow<Int> = MutableStateFlow(0)
            override val shuffleEnabled: StateFlow<Boolean> = MutableStateFlow(false)
            override val repeatMode: StateFlow<dev.nami.domain.RepeatMode> = MutableStateFlow(dev.nami.domain.RepeatMode.OFF)
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
        override suspend fun skipToPreviousTrack() {}
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
        override suspend fun setShuffleEnabled(enabled: Boolean) {}
        override suspend fun setRepeatMode(mode: dev.nami.domain.RepeatMode) {}
            override val activeLoop: StateFlow<dev.nami.domain.LoopRange?> = MutableStateFlow(null)
            override suspend fun setActiveLoop(loop: dev.nami.domain.LoopRange?) {}
        override val sleepTimerRemainingMs: StateFlow<Long?> = MutableStateFlow(null)
        override suspend fun startSleepTimer(durationMs: Long) {}
        override suspend fun cancelSleepTimer() {}
        override suspend fun stop() {}
    }

    private val libraryRepo = object : LibraryRepository {
        override fun tracks() = throw NotImplementedError()
            override suspend fun allTracksOrdered(): List<Track> = emptyList()
        override fun track(id: TrackId) = flowOf(trackFixture("t1", "/data/music/real-file.flac"))
        override fun albums() = throw NotImplementedError()
        override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
        override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
        override fun artists() = throw NotImplementedError()
        override fun album(id: dev.nami.core.model.AlbumId) = throw NotImplementedError()
        override fun artist(id: dev.nami.core.model.ArtistId) = throw NotImplementedError()
        override fun tracksInAlbum(id: dev.nami.core.model.AlbumId) = throw NotImplementedError()
        override fun tracksByArtist(id: dev.nami.core.model.ArtistId) = throw NotImplementedError()
        override fun albumsByArtist(id: dev.nami.core.model.ArtistId) = throw NotImplementedError()
        override suspend fun incrementPlayCount(id: dev.nami.core.model.TrackId) {}
        override suspend fun setTrackReplayGain(id: dev.nami.core.model.TrackId, gainDb: Float) {}
        override suspend fun import(source: ImportSource): Flow<dev.nami.domain.ImportProgress> = throw NotImplementedError()
        override suspend fun deleteTrack(id: TrackId) = throw NotImplementedError()
        override suspend fun deleteTracks(ids: List<TrackId>) {}
        override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
        override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
        override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
        override suspend fun renameAlbum(id: dev.nami.core.model.AlbumId, title: String) = error("unused")
        override suspend fun setAlbumCover(id: dev.nami.core.model.AlbumId, imageUri: String) = error("unused")
        override suspend fun setAlbumIsSingle(id: dev.nami.core.model.AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
        override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
        override suspend fun addTrackToAlbum(trackId: TrackId, albumId: dev.nami.core.model.AlbumId) = error("unused")
        override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
        override suspend fun addTrackToArtist(trackId: TrackId, artistId: dev.nami.core.model.ArtistId) = error("unused")
        override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
        override suspend fun renameArtist(id: dev.nami.core.model.ArtistId, name: String) = error("unused")
        override suspend fun setArtistPhoto(id: dev.nami.core.model.ArtistId, imageUri: String) = error("unused")
    }

    @Test
    fun `playTrack resolves path from library and starts playback with it`() {
        val playerRepo = RecordingPlayerRepository()
        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)

        viewModel.playTrack(TrackId("t1"))

        assertEquals(
            listOf(PlayableTrack(TrackId("t1"), "Window View", null, "/data/music/real-file.flac", format = "flac")),
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
                PlayableTrack(TrackId("t1"), "Window View", "Farewell225", "/data/music/a.flac", format = "flac"),
                PlayableTrack(TrackId("t2"), "Window View", "Farewell225", "/data/music/b.flac", format = "flac"),
            ),
            playerRepo.playedTracks,
        )
    }

    @Test
    fun `addToQueue forwards a mapped PlayableTrack`() {
        val playerRepo = RecordingPlayerRepository()
        val viewModel = NowPlayingViewModel(playerRepo, libraryRepo)

        viewModel.addToQueue(trackFixture("t1", "/data/music/a.flac"), artistName = "Farewell225")

        assertEquals(
            PlayableTrack(TrackId("t1"), "Window View", "Farewell225", "/data/music/a.flac", format = "flac"),
            playerRepo.addedTrack,
        )
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
