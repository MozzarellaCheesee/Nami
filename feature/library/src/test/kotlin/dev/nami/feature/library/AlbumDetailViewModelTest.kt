package dev.nami.feature.library

import androidx.lifecycle.SavedStateHandle
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            override fun recentAlbums(limit: Int) = flowOf(emptyList<AlbumSummary>())
            override fun featuredArtists(limit: Int) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override fun artists() = throw NotImplementedError()
            override fun album(id: AlbumId) = flowOf(album)
            override fun artist(id: ArtistId) = throw NotImplementedError()
            override fun tracksInAlbum(id: AlbumId) = flowOf(listOf(track))
            override suspend fun renameTrack(id: TrackId, title: String) = error("unused")
            override suspend fun setTrackCover(id: TrackId, imageUri: String) = error("unused")
            override suspend fun renameAlbum(id: AlbumId, title: String) = error("unused")
            override suspend fun createAlbum(title: String, artistId: ArtistId?): AlbumId = error("unused")
            override suspend fun deleteAlbum(id: AlbumId) = error("unused")
            override suspend fun setAlbumCover(id: AlbumId, imageUri: String) = error("unused")
            override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) = error("unused")
            override suspend fun setAlbumYear(id: AlbumId, year: Int?) = error("unused")
            override suspend fun setAlbumArtist(id: AlbumId, artistId: ArtistId?) = error("unused")
            override fun albumArtists(id: AlbumId) = flowOf(emptyList<dev.nami.core.model.Artist>())
            override suspend fun addAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun removeAlbumArtist(id: AlbumId, artistId: ArtistId) = error("unused")
            override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) = error("unused")
            override suspend fun removeTrackFromAlbum(trackId: TrackId) = error("unused")
            override suspend fun addTrackToArtist(trackId: TrackId, artistId: ArtistId) = error("unused")
            override suspend fun removeTrackFromArtist(trackId: TrackId) = error("unused")
            override suspend fun renameArtist(id: ArtistId, name: String) = error("unused")
            override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) = error("unused")
            override fun tracksByArtist(id: ArtistId) = throw NotImplementedError()
            override fun albumsByArtist(id: ArtistId) = throw NotImplementedError()
            override suspend fun incrementPlayCount(id: TrackId) {}
            override suspend fun setTrackReplayGain(id: TrackId, gainDb: Float) {}
            override suspend fun setTrackNote(id: TrackId, note: String?) {}
            override suspend fun import(source: ImportSource) = throw NotImplementedError()
            override suspend fun deleteTrack(id: TrackId) = throw NotImplementedError()
            override suspend fun deleteTracks(ids: List<TrackId>) {}
        }
        val fakePlayerRepo = object : PlayerRepository {
            override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
            override val queue: StateFlow<PlayerQueue> = MutableStateFlow(PlayerQueue.EMPTY)
            override val autoAdvanceSignal: StateFlow<Int> = MutableStateFlow(0)
            override val shuffleEnabled: StateFlow<Boolean> = MutableStateFlow(false)
            override val repeatMode: StateFlow<dev.nami.domain.RepeatMode> = MutableStateFlow(dev.nami.domain.RepeatMode.OFF)
            override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {}
            override suspend fun toggle() {}
            override suspend fun seek(ms: Long) {}
            override suspend fun skipNext() {}
            override suspend fun skipPrevious() {}
            override suspend fun skipToPreviousTrack() {}
            override suspend fun addToQueue(track: PlayableTrack) {}
            override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {}
            override suspend fun removeQueueItem(index: Int) {}
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
        val fakePlaylistRepo = object : PlaylistRepository {
            override fun playlists() = throw NotImplementedError()
            override fun playlist(id: dev.nami.core.model.PlaylistId) = throw NotImplementedError()
            override fun tracksInPlaylist(id: dev.nami.core.model.PlaylistId) = throw NotImplementedError()
            override suspend fun createPlaylist(name: String) = error("unused")
            override suspend fun renamePlaylist(id: dev.nami.core.model.PlaylistId, name: String) = error("unused")
            override suspend fun deletePlaylist(id: dev.nami.core.model.PlaylistId) = error("unused")
            override suspend fun setCoverImage(id: dev.nami.core.model.PlaylistId, imageUri: String) = error("unused")
            override suspend fun addTrack(playlistId: dev.nami.core.model.PlaylistId, trackId: TrackId) = error("unused")
            override suspend fun removeTrack(playlistId: dev.nami.core.model.PlaylistId, trackId: TrackId) = error("unused")
            override suspend fun exportM3u8(id: dev.nami.core.model.PlaylistId, destinationUri: String) = error("unused")
            override suspend fun importM3u8(sourceUri: String, playlistName: String) = error("unused")
            override fun isTrackLiked(trackId: TrackId) = flowOf(false)
            override suspend fun toggleLike(trackId: TrackId) = error("unused")
            override suspend fun likeTrack(trackId: TrackId) {}
        }
        val savedStateHandle = SavedStateHandle(mapOf("albumId" to "al1"))

        val viewModel = AlbumDetailViewModel(fakeRepo, fakePlayerRepo, fakePlaylistRepo, savedStateHandle)

        assertEquals(album, viewModel.uiState.value.album)
        assertEquals(listOf(track), viewModel.uiState.value.tracks)
    }
}
