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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
)

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    private data class TrackAndLyrics(val trackId: TrackId, val path: String, val lyrics: Lyrics?)

    private val trackAndLyrics: StateFlow<TrackAndLyrics?> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .distinctUntilChangedBy { it.trackId }
        .flatMapLatest { playing ->
            libraryRepository.track(playing.trackId).filterNotNull().flatMapLatest { track ->
                lyricsRepository.lyricsForPath(track.path).map { lyrics ->
                    TrackAndLyrics(playing.trackId, track.path, lyrics)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _positionMs = MutableStateFlow(0L)

    val uiState: StateFlow<LyricsUiState> = kotlinx.coroutines.flow.combine(trackAndLyrics, _positionMs) { tl, pos ->
        LyricsUiState(trackId = tl?.trackId, trackPath = tl?.path, lyrics = tl?.lyrics, positionMs = pos)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsUiState())

    init {
        playerRepository.state
            .onEach { state -> if (state is PlaybackState.Playing) _positionMs.value = state.positionMs }
            .launchIn(viewModelScope)
    }

    fun seekTo(ms: Long) {
        viewModelScope.launch { playerRepository.seek(ms) }
    }

    /** Manual sync: [lineTexts] in order, [stampedMs] the position captured for each as the user
     * tapped through the track -- same length, zipped 1:1 into the saved .lrc. */
    fun saveManualSync(lineTexts: List<String>, stampedMs: List<Long>) {
        val path = uiState.value.trackPath ?: return
        val lines = lineTexts.indices.map { i -> LyricLine(stampedMs.getOrElse(i) { 0L }, lineTexts[i]) }
        viewModelScope.launch { lyricsRepository.saveLyrics(path, Lyrics(lines)) }
    }
}
