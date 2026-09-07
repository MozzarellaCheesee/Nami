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
import dev.nami.player.waveform.WaveformScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playerRepository.state
    val queue: StateFlow<PlayerQueue> = playerRepository.queue
    val autoAdvanceSignal: StateFlow<Int> = playerRepository.autoAdvanceSignal

    /** Full Track for the "Аудиотракт"-style file details (bitrate/size/etc.) shown near the
     * format badge -- QueueTrack only carries what the mini/full player needs for display, not
     * the byte-level stuff, so this looks the real Track back up by id instead of growing
     * QueueTrack for one screen's use. */
    val currentTrackDetails: StateFlow<Track?> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .flatMapLatest { playing -> libraryRepository.track(playing.trackId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Real per-track waveform for the scrubber (see WaveformScanner) -- a full-track decode, so
    // it's scanned lazily off the main thread and cached (path -> bars) in memory for the
    // session, not persisted; re-decoding on every open of the same track this session would be
    // wasteful, but there's no DB column for it (would need a migration for a purely visual, easy
    // -to-recompute value). Null while loading/on failure -- WaveformScrubber falls back to its
    // own placeholder shape rather than showing nothing.
    private val waveformCache = LinkedHashMap<String, List<Float>>()
    private val _waveform = MutableStateFlow<List<Float>?>(null)
    val waveform: StateFlow<List<Float>?> = _waveform.asStateFlow()

    init {
        currentTrackDetails
            .map { it?.path }
            .distinctUntilChanged()
            .onEach { path -> loadWaveform(path) }
            .launchIn(viewModelScope)
    }

    private fun loadWaveform(path: String?) {
        if (path == null) {
            _waveform.value = null
            return
        }
        val cached = waveformCache[path]
        if (cached != null) {
            _waveform.value = cached
            return
        }
        _waveform.value = null
        viewModelScope.launch {
            val bars = withContext(Dispatchers.Default) { WaveformScanner.scan(path) } ?: return@launch
            // Cap the cache so a long listening session doesn't grow this unbounded -- each
            // track's own bar list is small (120 floats), but no reason to keep every track ever
            // played this session.
            if (waveformCache.size >= 30) waveformCache.remove(waveformCache.keys.first())
            waveformCache[path] = bars
            if (currentTrackDetails.value?.path == path) _waveform.value = bars
        }
    }

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
