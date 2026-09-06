package dev.nami.domain

import dev.nami.core.model.Lyrics
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
}
