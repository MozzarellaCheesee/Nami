package dev.nami.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.RepeatMode
import dev.nami.domain.SettingsRepository
import dev.nami.player.waveform.WaveformCache
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
    // Nullable with a default so plain-JVM unit tests (no Robolectric in this project) can keep
    // constructing this ViewModel with just the two repositories, same as before this field
    // existed -- WaveformCache itself no-ops (returns null / does nothing) when context is null.
    @ApplicationContext context: Context? = null,
    // Also nullable/defaulted for the same reason -- only used for the night-mode pill, every
    // existing test constructs this ViewModel with just the two repositories.
    private val settingsRepository: SettingsRepository? = null,
    // Same reasoning -- only used for the Now Playing heart/like button.
    private val playlistRepository: PlaylistRepository? = null,
) : ViewModel() {

    private val waveformDiskCache = WaveformCache(context)

    val playbackState: StateFlow<PlaybackState> = playerRepository.state
    val queue: StateFlow<PlayerQueue> = playerRepository.queue
    val autoAdvanceSignal: StateFlow<Int> = playerRepository.autoAdvanceSignal
    val shuffleEnabled: StateFlow<Boolean> = playerRepository.shuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = playerRepository.repeatMode
    val sleepTimerRemainingMs: StateFlow<Long?> = playerRepository.sleepTimerRemainingMs
    val nightModeEnabled: StateFlow<Boolean> = settingsRepository?.nightModeEnabled
        ?: MutableStateFlow(false)

    /** Real like state for whatever's currently playing -- see PlaylistRepository.isTrackLiked
     * (backed by the "Любимые треки" system playlist, not a separate favorites concept). */
    val isCurrentTrackLiked: StateFlow<Boolean> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { trackId -> playlistRepository?.isTrackLiked(trackId) ?: kotlinx.coroutines.flow.flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleLikeCurrentTrack() {
        val trackId = (playbackState.value as? PlaybackState.Playing)?.trackId ?: return
        val repo = playlistRepository ?: return
        viewModelScope.launch { repo.toggleLike(trackId) }
    }

    /** Full Track for the "Аудиотракт"-style file details (bitrate/size/etc.) shown near the
     * format badge -- QueueTrack only carries what the mini/full player needs for display, not
     * the byte-level stuff, so this looks the real Track back up by id instead of growing
     * QueueTrack for one screen's use. */
    val currentTrackDetails: StateFlow<Track?> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .flatMapLatest { playing -> libraryRepository.track(playing.trackId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Real per-track waveform for the scrubber (see WaveformScanner) -- a full-track decode, so
    // it's scanned lazily off the main thread. Two-tier cache: an in-memory map for instant reuse
    // within this session, backed by WaveformCache on disk so a track already scanned in a
    // PREVIOUS session doesn't flash the placeholder shape again after an app restart while it
    // re-decodes the whole file just to reproduce the same 120 numbers as last time. Null while
    // loading/on failure -- WaveformScrubber falls back to its own placeholder shape.
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
        val memoryCached = waveformCache[path]
        if (memoryCached != null) {
            _waveform.value = memoryCached
            return
        }
        _waveform.value = null
        viewModelScope.launch {
            val diskCached = withContext(Dispatchers.IO) { waveformDiskCache.read(path) }
            if (diskCached != null) {
                rememberWaveform(path, diskCached)
                return@launch
            }
            val scanned = withContext(Dispatchers.Default) { WaveformScanner.scan(path) } ?: return@launch
            rememberWaveform(path, scanned)
            withContext(Dispatchers.IO) { waveformDiskCache.write(path, scanned) }
        }
    }

    private fun rememberWaveform(path: String, bars: List<Float>) {
        // Cap the in-memory cache so a long listening session doesn't grow this unbounded -- each
        // track's own bar list is small (120 floats), but no reason to keep every track ever
        // played this session in RAM; the disk cache already covers "seen it before".
        if (waveformCache.size >= 30) waveformCache.remove(waveformCache.keys.first())
        waveformCache[path] = bars
        if (currentTrackDetails.value?.path == path) _waveform.value = bars
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

    /** "Перемешать" from Album/Artist -- starts the given tracks as a fresh queue, then
     * immediately shuffles it (see [PlayerRepository.setShuffleEnabled]) rather than shuffling
     * [tracks] here and starting at index 0: going through the real toggle means the player's own
     * shuffle button can un-shuffle back to this exact starting order afterwards, same as if the
     * user had tapped Play and then shuffle by hand. */
    fun playTracksShuffled(tracks: List<Track>, artistName: String?) {
        viewModelScope.launch {
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName) }, startIndex = 0)
            playerRepository.setShuffleEnabled(true)
            _externalTrackChangeSignal.value++
        }
    }

    fun toggleShuffle() {
        viewModelScope.launch { playerRepository.setShuffleEnabled(!playerRepository.shuffleEnabled.value) }
    }

    /** OFF -> ALL -> ONE -> OFF, the standard three-state cycle every music player's repeat
     * button uses. */
    fun cycleRepeatMode() {
        val next = when (playerRepository.repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        viewModelScope.launch { playerRepository.setRepeatMode(next) }
    }

    fun toggleNightMode() {
        val repo = settingsRepository ?: return
        repo.setNightModeEnabled(!repo.nightModeEnabled.value)
    }

    fun startSleepTimer(durationMs: Long) {
        viewModelScope.launch { playerRepository.startSleepTimer(durationMs) }
    }

    fun cancelSleepTimer() {
        viewModelScope.launch { playerRepository.cancelSleepTimer() }
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
