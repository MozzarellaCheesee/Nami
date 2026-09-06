package dev.nami.domain

import dev.nami.core.model.Lyrics
import dev.nami.core.model.WordTiming
import dev.nami.core.model.WordToken
import kotlinx.coroutines.flow.Flow

/** Keyed by the track's file path (not id) -- lyrics live as a plain sibling .lrc file next to
 * the audio (same basename), same as most desktop players expect, so they survive a re-import
 * and are visible/editable outside the app too.
 *
 * Only the local .lrc / manual-entry source from План.md's list is implemented so far -- reading
 * an embedded USLT/LYRICS tag and fetching from LRCLIB both need work this pass didn't include
 * (native tag reader doesn't expose lyrics tags yet; LRCLIB is a plain network call, deferred
 * with everything else that isn't "make synced lyrics work offline first"). */
interface LyricsRepository {
    fun lyricsForPath(path: String): Flow<Lyrics?>
    suspend fun saveLyrics(path: String, lyrics: Lyrics)

    /** LRCLIB (lrclib.net) -- free, keyless, exists specifically for synced lyrics lookup by
     * title/artist/duration. Null on no match, instrumental track, or any network/parse failure;
     * callers treat that the same as "nothing found" and fall through to manual entry. */
    suspend fun fetchFromLrcLib(title: String, artistName: String?, durationMs: Long): Lyrics?

    /** Cached translation, one line per original lyric line, same order -- a sibling file next to
     * the .lrc so it survives restarts and never needs re-translating once done. */
    fun translationForPath(path: String): Flow<List<String>?>
    suspend fun saveTranslation(path: String, lines: List<String>)

    /** On-device (ML Kit) translation to Russian -- the model downloads once over network on
     * first use per language pair, then runs fully offline. Null on download/translate failure. */
    suspend fun translateToRussian(lines: List<String>): List<String>?

    /** Same cache/generation shape as translation, whole-line Hepburn romaji instead of a
     * per-kanji reading overlay -- План.md's "romaji / оригинал / перевод" triplet mode. */
    fun romajiForPath(path: String): Flow<List<String>?>
    suspend fun saveRomaji(path: String, lines: List<String>)
    suspend fun generateRomaji(lines: List<String>): List<String>

    /** Splits a line into words for furigana ruby-text display and tap-to-dictionary -- the same
     * split for both, computed live (Kuromoji tokenizing one short line is fast enough not to
     * need caching, unlike a whole-track translation/romaji pass). */
    suspend fun tokenizeLine(line: String): List<WordToken>

    /** Result of a WhisperAligner pass, one word list per [LyricLine] (outer index matches
     * [Lyrics.lines]) -- cached next to the .lrc so the ~minute-long alignment only runs once
     * per track. Null when no precise sync has been run yet. */
    fun wordTimingsForPath(path: String): Flow<List<List<WordTiming>>?>
    suspend fun saveWordTimings(path: String, perLine: List<List<WordTiming>>)
}
