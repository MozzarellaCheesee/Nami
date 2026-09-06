package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playerRepository.state
    val queue: StateFlow<PlayerQueue> = playerRepository.queue

    private val _externalTrackChangeSignal = MutableStateFlow(0)
    /** Bumped by playTrack/playFromLibrary/playTracks -- the "a track was selected from a list tap"
     * entry points -- but never by skipNext/skipPrevious, so MiniPlayer can play its track-change
     * slide animation for taps without it colliding with the animation a manual swipe already
     * plays for itself. */
    val externalTrackChangeSignal: StateFlow<Int> = _externalTrackChangeSignal.asStateFlow()

    fun playTrack(trackId: TrackId) {
        viewModelScope.launch {
            val track = libraryRepository.track(trackId).first() ?: return@launch
            playerRepository.play(listOf(track.toPlayableTrack(artistName = null)), startIndex = 0)
            _externalTrackChangeSignal.value++
        }
    }

    /**
     * Plays [trackId] as if it were tapped from the full "all tracks" library list: the queue is
     * every track in that list, positioned at [trackId], so skipPrevious/skipNext traverse the
     * whole library exactly like tapping a track inside an album/artist/playlist already does.
     */
    fun playFromLibrary(trackId: TrackId) {
        viewModelScope.launch {
            val tracks = libraryRepository.allTracksOrdered()
            val startIndex = tracks.indexOfFirst { it.id == trackId }
            if (startIndex < 0) return@launch
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName = null) }, startIndex = startIndex)
            _externalTrackChangeSignal.value++
        }
    }

    fun playTracks(tracks: List<Track>, artistName: String?, startIndex: Int) {
        viewModelScope.launch {
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName) }, startIndex = startIndex)
            _externalTrackChangeSignal.value++
        }
    }

    fun addToQueue(track: Track, artistName: String?) {
        viewModelScope.launch { playerRepository.addToQueue(track.toPlayableTrack(artistName)) }
    }

    fun toggle() {
        viewModelScope.launch { playerRepository.toggle() }
    }

    fun seek(ms: Long) {
        viewModelScope.launch { playerRepository.seek(ms) }
    }

    fun stop() {
        viewModelScope.launch { playerRepository.stop() }
    }

    fun skipNext() {
        viewModelScope.launch { playerRepository.skipNext() }
    }

    fun skipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    /** For swipe gestures -- see [PlayerRepository.skipToPreviousTrack]. */
    fun skipToPreviousTrack() {
        viewModelScope.launch { playerRepository.skipToPreviousTrack() }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { playerRepository.moveQueueItem(fromIndex, toIndex) }
    }

    fun removeQueueItem(index: Int) {
        viewModelScope.launch { playerRepository.removeQueueItem(index) }
    }

    private fun Track.toPlayableTrack(artistName: String?): PlayableTrack =
        PlayableTrack(
            id = id,
            title = title,
            artistName = artistName ?: this.artistName,
            path = path,
            artworkPath = albumArtworkPath,
            format = format,
        )
}
