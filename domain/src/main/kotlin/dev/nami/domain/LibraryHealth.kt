package dev.nami.domain

import dev.nami.core.model.TrackId

/** П.md §23.18 "Здоровье библиотеки". Deliberately doesn't include "фейковый hi-res" -- that
 * needs spectral analysis (checking for content above ~22kHz) which no part of this codebase
 * does; adding a fake/guessed version of that check would be worse than omitting it. */
data class HealthTrackRef(val id: TrackId, val title: String)

data class LibraryHealthReport(
    val tracksWithoutArtwork: List<HealthTrackRef>,
    val tracksWithoutLyrics: List<HealthTrackRef>,
    val albumsWithoutYear: List<String>,
    /** Each inner list is one suspected-duplicate group (2+ tracks), grouped by
     * title+artist+~duration -- a heuristic, not a real audio fingerprint (none exists in this
     * codebase either). */
    val duplicateGroups: List<List<HealthTrackRef>>,
    /** Track's file no longer exists at its recorded path -- moved, deleted outside the app, or
     * external storage unmounted. */
    val missingFiles: List<HealthTrackRef>,
    /** Artist names that differ only by case/whitespace (e.g. "Farewell225" vs "farewell225 ")
     * ended up as separate Artist rows -- each inner list is one such group of raw names. */
    val inconsistentArtistNameGroups: List<List<String>>,
)
