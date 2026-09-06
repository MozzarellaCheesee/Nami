package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.LyricLine
import dev.nami.core.model.Lyrics
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LyricsUiState(
    val trackId: TrackId? = null,
    val trackPath: String? = null,
    val lyrics: Lyrics? = null,
    val positionMs: Long = 0,
    val isFetchingOnline: Boolean = false,
)

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    private data class TrackAndLyrics(
        val trackId: TrackId,
        val path: String,
        val title: String,
        val artistName: String?,
        val durationMs: Long,
        val lyrics: Lyrics?,
    )

    // Bumped after a successful LRCLIB fetch is saved to the sidecar .lrc, so lyricsForPath (a
    // one-shot read, not a file watcher) gets re-read and picks up what was just written.
    private val reloadSignal = MutableStateFlow(0)

    private val trackAndLyrics: StateFlow<TrackAndLyrics?> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .flatMapLatest { playing ->
            libraryRepository.track(playing.trackId).filterNotNull().flatMapLatest { track ->
                reloadSignal.flatMapLatest { _ ->
                    lyricsRepository.lyricsForPath(track.path).map { lyrics ->
                        TrackAndLyrics(track.id, track.path, track.title, track.artistName, track.durationMs, lyrics)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _positionMs = MutableStateFlow(0L)
    private val _isFetchingOnline = MutableStateFlow(false)
    // Never re-hit LRCLIB for a track once tried this session, hit or miss -- there is no
    // "retry automatically forever" here, only the one manual re-check the user can trigger from
    // the empty state (also routed through fetchOnline, but that call bypasses this guard).
    private val triedOnlineFetch = mutableSetOf<TrackId>()

    val uiState: StateFlow<LyricsUiState> = combine(trackAndLyrics, _positionMs, _isFetchingOnline) { tl, pos, fetching ->
        LyricsUiState(
            trackId = tl?.trackId,
            trackPath = tl?.path,
            lyrics = tl?.lyrics,
            positionMs = pos,
            isFetchingOnline = fetching,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsUiState())

    init {
        playerRepository.state
            .onEach { state -> if (state is PlaybackState.Playing) _positionMs.value = state.positionMs }
            .launchIn(viewModelScope)

        // The actual "minimum effort" part: no local .lrc found -> try LRCLIB automatically,
        // once per track, before ever bothering the user with the manual-entry empty state.
        trackAndLyrics
            .filterNotNull()
            .onEach { tl ->
                if (tl.lyrics == null && triedOnlineFetch.add(tl.trackId)) {
                    fetchOnline(tl.trackId, tl.path, tl.title, tl.artistName, tl.durationMs)
                }
            }
            .launchIn(viewModelScope)
    }

    /** Also callable directly from the UI's "Искать в сети" retry -- passing the current track's
     * own already-known fields lets a miss be retried without re-adding the guard above. */
    fun retryOnlineFetch() {
        val tl = trackAndLyrics.value ?: return
        fetchOnline(tl.trackId, tl.path, tl.title, tl.artistName, tl.durationMs)
    }

    private fun fetchOnline(trackId: TrackId, path: String, title: String, artistName: String?, durationMs: Long) {
        viewModelScope.launch {
            _isFetchingOnline.value = true
            try {
                val fetched = lyricsRepository.fetchFromLrcLib(title, artistName, durationMs)
                if (fetched != null && fetched.lines.isNotEmpty()) {
                    lyricsRepository.saveLyrics(path, fetched)
                    reloadSignal.value++
                }
            } finally {
                _isFetchingOnline.value = false
            }
        }
    }

    fun seekTo(ms: Long) {
        viewModelScope.launch { playerRepository.seek(ms) }
    }

    /** Manual sync: [lineTexts] in order, [stampedMs] the position captured for each as the user
     * tapped through the track -- same length, zipped 1:1 into the saved .lrc. */
    fun saveManualSync(lineTexts: List<String>, stampedMs: List<Long>) {
        val path = uiState.value.trackPath ?: return
        val lines = lineTexts.indices.map { i -> LyricLine(stampedMs.getOrElse(i) { 0L }, lineTexts[i]) }
        viewModelScope.launch {
            lyricsRepository.saveLyrics(path, Lyrics(lines))
            reloadSignal.value++
        }
    }
}
