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
    val isFetchingOnline: Boolean = false,
    val translation: List<String>? = null,
    val showTranslation: Boolean = false,
    val isTranslating: Boolean = false,
    val furigana: List<String>? = null,
    val showFurigana: Boolean = false,
    val isGeneratingFurigana: Boolean = false,
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
        val translation: List<String>?,
        val furigana: List<String>?,
    )

    // Bumped after a successful LRCLIB fetch/manual save/translation is written to its sidecar
    // file, so the (one-shot, not file-watching) reads below get re-run and pick up what was
    // just written.
    private val reloadSignal = MutableStateFlow(0)

    private val trackAndLyrics: StateFlow<TrackAndLyrics?> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .flatMapLatest { playing ->
            libraryRepository.track(playing.trackId).filterNotNull().flatMapLatest { track ->
                reloadSignal.flatMapLatest { _ ->
                    combine(
                        lyricsRepository.lyricsForPath(track.path),
                        lyricsRepository.translationForPath(track.path),
                        lyricsRepository.furiganaForPath(track.path),
                    ) { lyrics, translation, furigana ->
                        TrackAndLyrics(track.id, track.path, track.title, track.artistName, track.durationMs, lyrics, translation, furigana)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _positionMs = MutableStateFlow(0L)
    private val _isFetchingOnline = MutableStateFlow(false)
    private val _showTranslation = MutableStateFlow(false)
    private val _isTranslating = MutableStateFlow(false)
    private val _showFurigana = MutableStateFlow(false)
    private val _isGeneratingFurigana = MutableStateFlow(false)
    // Never re-hit LRCLIB for a track once tried this session, hit or miss -- there is no
    // "retry automatically forever" here, only the one manual re-check the user can trigger from
    // the empty state (also routed through fetchOnline, but that call bypasses this guard).
    private val triedOnlineFetch = mutableSetOf<TrackId>()

    val uiState: StateFlow<LyricsUiState> = combine(
        trackAndLyrics, _positionMs, _isFetchingOnline, _showTranslation, _isTranslating,
        _showFurigana, _isGeneratingFurigana,
    ) { values ->
        val tl = values[0] as TrackAndLyrics?
        val pos = values[1] as Long
        val fetching = values[2] as Boolean
        val showTranslation = values[3] as Boolean
        val translating = values[4] as Boolean
        val showFurigana = values[5] as Boolean
        val generatingFurigana = values[6] as Boolean
        LyricsUiState(
            trackId = tl?.trackId,
            trackPath = tl?.path,
            lyrics = tl?.lyrics,
            positionMs = pos,
            isFetchingOnline = fetching,
            translation = tl?.translation,
            showTranslation = showTranslation,
            isTranslating = translating,
            furigana = tl?.furigana,
            showFurigana = showFurigana,
            isGeneratingFurigana = generatingFurigana,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsUiState())

    init {
        playerRepository.state
            .onEach { state -> if (state is PlaybackState.Playing) _positionMs.value = state.positionMs }
            .launchIn(viewModelScope)

        // The show-translation/show-furigana toggles are per-track, not global: without this,
        // switching to a track with neither cached left both header buttons lit orange ("on")
        // carried over from the previous track, while nothing was actually shown.
        trackAndLyrics
            .filterNotNull()
            .distinctUntilChangedBy { it.trackId }
            .onEach {
                _showTranslation.value = false
                _showFurigana.value = false
            }
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

    /** The button in the header: on first tap for a track with no cached translation, kicks off
     * an ML Kit translate (downloads its offline model on first-ever use) and caches the result
     * next to the .lrc; every tap after that is just show/hide, no repeat network/CPU work. */
    fun toggleTranslation() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        if (_showTranslation.value) {
            _showTranslation.value = false
            return
        }
        _showTranslation.value = true
        if (tl.translation == null) runTranslation(tl.path, tl.lyrics)
    }

    /** Long-press on the translate button: re-runs it even if a (possibly bad -- garbled by an
     * old bug, or just wrong) cached translation already exists, overwriting the cache. */
    fun forceRetranslate() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        _showTranslation.value = true
        runTranslation(tl.path, tl.lyrics)
    }

    private fun runTranslation(path: String, lyrics: Lyrics) {
        viewModelScope.launch {
            _isTranslating.value = true
            try {
                val translated = lyricsRepository.translateToRussian(lyrics.lines.map { it.text })
                if (translated != null) {
                    lyricsRepository.saveTranslation(path, translated)
                    reloadSignal.value++
                }
            } finally {
                _isTranslating.value = false
            }
        }
    }

    /** Same shape as [toggleTranslation] but Kuromoji runs fully on-device -- no network, no
     * model download -- so the only reason to cache it at all is to not re-tokenize every time
     * the screen reopens. */
    fun toggleFurigana() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        if (_showFurigana.value) {
            _showFurigana.value = false
            return
        }
        _showFurigana.value = true
        if (tl.furigana == null) runFurigana(tl.path, tl.lyrics)
    }

    /** Long-press on the furigana button: re-runs it even over an existing cache -- same escape
     * hatch as [forceRetranslate], for a stale/bad cached result. */
    fun forceRegenerateFurigana() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        _showFurigana.value = true
        runFurigana(tl.path, tl.lyrics)
    }

    private fun runFurigana(path: String, lyrics: Lyrics) {
        viewModelScope.launch {
            _isGeneratingFurigana.value = true
            try {
                val generated = lyricsRepository.generateFurigana(lyrics.lines.map { it.text })
                lyricsRepository.saveFurigana(path, generated)
                reloadSignal.value++
            } finally {
                _isGeneratingFurigana.value = false
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
