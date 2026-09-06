package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.DictionaryEntry
import dev.nami.core.model.LyricLine
import dev.nami.core.model.Lyrics
import dev.nami.core.model.TrackId
import dev.nami.core.model.WordTiming
import dev.nami.core.model.WordToken
import dev.nami.domain.DictionaryRepository
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.VocabularyRepository
import dev.nami.domain.WhisperAligner
import dev.nami.domain.WordTimingMatcher
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

data class WordLookup(val token: WordToken, val entries: List<DictionaryEntry>, val contextLine: String)

data class LyricsUiState(
    val trackId: TrackId? = null,
    val trackPath: String? = null,
    val trackTitle: String? = null,
    val lyrics: Lyrics? = null,
    val positionMs: Long = 0,
    val isFetchingOnline: Boolean = false,
    val translation: List<String>? = null,
    val showTranslation: Boolean = false,
    val isTranslating: Boolean = false,
    val showFurigana: Boolean = false,
    val romaji: List<String>? = null,
    val showRomaji: Boolean = false,
    val isGeneratingRomaji: Boolean = false,
    val wordLookup: WordLookup? = null,
    val wordTimings: List<List<WordTiming>>? = null,
    val preciseSyncSupported: Boolean = false,
    val isPreciseSyncing: Boolean = false,
    val preciseSyncProgress: Float = 0f,
)

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val whisperAligner: WhisperAligner,
) : ViewModel() {

    private data class TrackAndLyrics(
        val trackId: TrackId,
        val path: String,
        val title: String,
        val artistName: String?,
        val durationMs: Long,
        val lyrics: Lyrics?,
        val translation: List<String>?,
        val romaji: List<String>?,
        val wordTimings: List<List<WordTiming>>?,
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
                        lyricsRepository.romajiForPath(track.path),
                        lyricsRepository.wordTimingsForPath(track.path),
                    ) { lyrics, translation, romaji, wordTimings ->
                        TrackAndLyrics(track.id, track.path, track.title, track.artistName, track.durationMs, lyrics, translation, romaji, wordTimings)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _positionMs = MutableStateFlow(0L)
    private val _isFetchingOnline = MutableStateFlow(false)
    private val _showTranslation = MutableStateFlow(false)
    private val _isTranslating = MutableStateFlow(false)
    // Live, not cached -- unlike translation/romaji, tokenizing one line with Kuromoji is fast
    // enough that furigana doesn't need generation/caching at all, just a display toggle.
    private val _showFurigana = MutableStateFlow(false)
    private val _showRomaji = MutableStateFlow(false)
    private val _isGeneratingRomaji = MutableStateFlow(false)
    private val _wordLookup = MutableStateFlow<WordLookup?>(null)
    private val _isPreciseSyncing = MutableStateFlow(false)
    private val _preciseSyncProgress = MutableStateFlow(0f)
    // Never re-hit LRCLIB for a track once tried this session, hit or miss -- there is no
    // "retry automatically forever" here, only the one manual re-check the user can trigger from
    // the empty state (also routed through fetchOnline, but that call bypasses this guard).
    private val triedOnlineFetch = mutableSetOf<TrackId>()

    val uiState: StateFlow<LyricsUiState> = combine(
        trackAndLyrics, _positionMs, _isFetchingOnline, _showTranslation, _isTranslating,
        _showFurigana, _showRomaji, _isGeneratingRomaji, _wordLookup, _isPreciseSyncing, _preciseSyncProgress,
    ) { values ->
        val tl = values[0] as TrackAndLyrics?
        val pos = values[1] as Long
        val fetching = values[2] as Boolean
        val showTranslation = values[3] as Boolean
        val translating = values[4] as Boolean
        val showFurigana = values[5] as Boolean
        val showRomaji = values[6] as Boolean
        val generatingRomaji = values[7] as Boolean
        @Suppress("UNCHECKED_CAST")
        val wordLookup = values[8] as WordLookup?
        val preciseSyncing = values[9] as Boolean
        val preciseSyncProgress = values[10] as Float
        LyricsUiState(
            trackId = tl?.trackId,
            trackPath = tl?.path,
            trackTitle = tl?.title,
            lyrics = tl?.lyrics,
            positionMs = pos,
            isFetchingOnline = fetching,
            translation = tl?.translation,
            showTranslation = showTranslation,
            isTranslating = translating,
            showFurigana = showFurigana,
            romaji = tl?.romaji,
            showRomaji = showRomaji,
            isGeneratingRomaji = generatingRomaji,
            wordLookup = wordLookup,
            wordTimings = tl?.wordTimings,
            preciseSyncSupported = whisperAligner.isSupported(),
            isPreciseSyncing = preciseSyncing,
            preciseSyncProgress = preciseSyncProgress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsUiState())

    init {
        playerRepository.state
            .onEach { state -> if (state is PlaybackState.Playing) _positionMs.value = state.positionMs }
            .launchIn(viewModelScope)

        // The show-translation/show-furigana/show-romaji toggles are per-track, not global:
        // without this, switching to a track with nothing cached left the header buttons lit
        // orange ("on") carried over from the previous track, while nothing was actually shown.
        trackAndLyrics
            .filterNotNull()
            .distinctUntilChangedBy { it.trackId }
            .onEach {
                _showTranslation.value = false
                _showFurigana.value = false
                _showRomaji.value = false
                _wordLookup.value = null
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

    fun toggleFurigana() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        _showFurigana.value = !_showFurigana.value
    }

    /** Same shape as [toggleTranslation], whole-line romaji instead of per-kanji readings. */
    fun toggleRomaji() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        if (_showRomaji.value) {
            _showRomaji.value = false
            return
        }
        _showRomaji.value = true
        if (tl.romaji == null) runRomaji(tl.path, tl.lyrics)
    }

    fun forceRegenerateRomaji() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null) return
        _showRomaji.value = true
        runRomaji(tl.path, tl.lyrics)
    }

    private fun runRomaji(path: String, lyrics: Lyrics) {
        viewModelScope.launch {
            _isGeneratingRomaji.value = true
            try {
                val generated = lyricsRepository.generateRomaji(lyrics.lines.map { it.text })
                lyricsRepository.saveRomaji(path, generated)
                reloadSignal.value++
            } finally {
                _isGeneratingRomaji.value = false
            }
        }
    }

    /** Splits a line into tappable words -- used for both the furigana ruby-text layout and the
     * word-tap dictionary lookup below, so the two always agree on word boundaries. */
    suspend fun tokenizeLine(line: String): List<WordToken> = lyricsRepository.tokenizeLine(line)

    /** Tap a word in the lyrics -> look it up by its dictionary (base) form, not the conjugated
     * surface form actually printed -- JMdict headwords are citation forms ("食べる", not "食べた"). */
    fun lookupWord(token: WordToken, contextLine: String) {
        viewModelScope.launch {
            val entries = dictionaryRepository.lookup(token.baseForm)
            _wordLookup.value = WordLookup(token, entries, contextLine)
        }
    }

    fun dismissWordLookup() {
        _wordLookup.value = null
    }

    /** "В словарик" from the word lookup popup -- saved with the line/track it came from, per
     * План.md's "слова из песен с контекстной строкой и ссылкой на трек". */
    fun addToVocabulary(word: String, reading: String, meaning: String, contextLine: String) {
        val trackTitle = uiState.value.trackTitle ?: return
        viewModelScope.launch { vocabularyRepository.add(word, reading, meaning, contextLine, trackTitle) }
    }

    fun seekTo(ms: Long) {
        viewModelScope.launch { playerRepository.seek(ms) }
    }

    /** "Точная синхронизация" -- runs on-device whisper.cpp word-level alignment over the actual
     * track audio and replaces the linear-interpolation karaoke sweep with real timing. Downloads
     * the ~500MB model on first use. Arm64-v8a only (gated in the UI via preciseSyncSupported). */
    fun runPreciseSync() {
        val tl = trackAndLyrics.value
        if (tl?.lyrics == null || _isPreciseSyncing.value) return
        viewModelScope.launch {
            _isPreciseSyncing.value = true
            _preciseSyncProgress.value = 0f
            try {
                if (!whisperAligner.isModelDownloaded()) {
                    val downloaded = whisperAligner.downloadModel { progress -> _preciseSyncProgress.value = progress * 0.5f }
                    if (!downloaded) return@launch
                }
                _preciseSyncProgress.value = 0.5f
                val words = whisperAligner.alignWords(tl.path, language = "ja") ?: return@launch
                val perLine = WordTimingMatcher.match(tl.lyrics, words)
                lyricsRepository.saveWordTimings(tl.path, perLine)
                reloadSignal.value++
            } finally {
                _isPreciseSyncing.value = false
                _preciseSyncProgress.value = 0f
            }
        }
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
