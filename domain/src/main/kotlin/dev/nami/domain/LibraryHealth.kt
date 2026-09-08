package dev.nami.domain

import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId

/** П.md §23.18 "Здоровье библиотеки". Deliberately doesn't include "фейковый hi-res" - that
 * needs spectral analysis (checking for content above ~22kHz) which no part of this codebase
 * does; adding a fake/guessed version of that check would be worse than omitting it. */
data class HealthTrackRef(val id: TrackId, val title: String)

data class HealthAlbumRef(val id: AlbumId, val title: String)

data class HealthArtistRef(val id: ArtistId, val name: String)

data class LibraryHealthReport(
    /** No auto-fix - no artwork source (embedded-only extraction happens at import time, no
     * online cover fetch exists in this codebase) to retry from, same "no fake fix" stance as
     * the missing hi-res check. */
    val tracksWithoutArtwork: List<HealthTrackRef>,
    val tracksWithoutLyrics: List<HealthTrackRef>,
    val albumsWithoutYear: List<HealthAlbumRef>,
    /** Each inner list is one suspected-duplicate group (2+ tracks), grouped by
     * title+artist+~duration - a heuristic, not a real audio fingerprint (none exists in this
     * codebase either). */
    val duplicateGroups: List<List<HealthTrackRef>>,
    /** Track's file no longer exists at its recorded path - moved, deleted outside the app, or
     * external storage unmounted. */
    val missingFiles: List<HealthTrackRef>,
    /** Artist rows whose names differ only by case/whitespace (e.g. "Farewell225" vs
     * "farewell225 ") - each inner list is one such group, "вылечить" renames every row to the
     * first (already-canonical-looking) name in the group. */
    val inconsistentArtistNameGroups: List<List<HealthArtistRef>>,
)
