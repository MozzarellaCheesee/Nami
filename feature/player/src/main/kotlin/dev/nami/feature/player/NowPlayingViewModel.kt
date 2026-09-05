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
import kotlinx.coroutines.flow.StateFlow
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

    fun playTrack(trackId: TrackId) {
        viewModelScope.launch {
            val track = libraryRepository.track(trackId).first() ?: return@launch
            playerRepository.play(listOf(track.toPlayableTrack(artistName = null)), startIndex = 0)
        }
    }

    fun playTracks(tracks: List<Track>, artistName: String?, startIndex: Int) {
        viewModelScope.launch {
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName) }, startIndex = startIndex)
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

    fun skipNext() {
        viewModelScope.launch { playerRepository.skipNext() }
    }

    fun skipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { playerRepository.moveQueueItem(fromIndex, toIndex) }
    }

    fun removeQueueItem(index: Int) {
        viewModelScope.launch { playerRepository.removeQueueItem(index) }
    }

    private fun Track.toPlayableTrack(artistName: String?): PlayableTrack =
        PlayableTrack(id = id, title = title, artistName = artistName ?: this.artistName, path = path, artworkPath = albumArtworkPath)
}
