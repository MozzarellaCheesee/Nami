package dev.nami.player

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.PlayerQueue
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin
import dev.nami.domain.QueueTrack
import kotlin.math.abs

data class MediaItemInfo(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkPath: String? = null,
    val format: String? = null,
)

fun buildPlayerQueue(
    nowPlaying: MediaItemInfo?,
    upcoming: List<MediaItemInfo>,
    originByMediaId: Map<String, QueueOrigin>,
    previous: MediaItemInfo? = null,
): PlayerQueue {
    val nowPlayingTrack = nowPlaying?.let {
        QueueTrack(TrackId(it.mediaId), it.title, it.artist, it.artworkPath, it.format)
    }
    val upcomingItems = upcoming.map { info ->
        QueueItem(
            track = QueueTrack(TrackId(info.mediaId), info.title, info.artist, info.artworkPath, info.format),
            origin = originByMediaId[info.mediaId] ?: QueueOrigin.CONTEXT,
        )
    }
    val previousTrack = previous?.let {
        QueueTrack(TrackId(it.mediaId), it.title, it.artist, it.artworkPath, it.format)
    }
    return PlayerQueue(nowPlayingTrack, upcomingItems, previousTrack)
}

private const val ARTIST_SPACING_WINDOW = 5
private const val MAX_ADJACENT_BPM_DIFF = 40f
private const val SKIP_COUNT_THRESHOLD = 3

/** Этап 6's "правила автоочереди" (План.md §22.13) - a best-effort local repair pass over an
 * already-ordered (e.g. shuffled) list, not a full constraint solver: for each position that
 * violates a rule against what's already placed before it, this looks forward for the nearest
 * later track that WOULD satisfy every rule there and swaps it in. If nothing later fits, the
 * violation is left in place rather than giving up more of the list's own order than necessary --
 * a shuffle that's 95% rule-clean beats one that got reshuffled from scratch chasing the last 5%.
 *
 * Rules applied, in order of how much they weigh into a "good fit" check:
 * - Same artist as anything within the last [ARTIST_SPACING_WINDOW] positions -> avoid.
 * - Same album as the immediately preceding track -> avoid.
 * - BPM more than [MAX_ADJACENT_BPM_DIFF] away from the immediately preceding track's BPM ->
 *   avoid (only when both tracks actually have a scanned BPM; unscanned tracks never trigger this).
 * - Skipped 3+ times (Track.skipCount) -> only avoided as a last resort, when there is some other
 *   candidate later in the list that doesn't also fail the harder rules above; a heavily-skipped
 *   track still plays rather than being silently dropped from the queue.
 */
fun applyAutoQueueRules(tracks: List<Track>): List<Track> {
    if (tracks.size < 2) return tracks
    val result = tracks.toMutableList()
    for (i in 1 until result.size) {
        if (fitsAt(result, i, result[i], allowSkipped = true)) continue
        // Only ever searches j > i, so swapping list[j] into position i never touches or
        // reorders anything before i - the window/previous-track checks below can read straight
        // from the untouched list instead of simulating the move on a copy each time.
        val swapIndex = (i + 1 until result.size).firstOrNull { j -> fitsAt(result, i, result[j], allowSkipped = false) }
            ?: (i + 1 until result.size).firstOrNull { j -> fitsAt(result, i, result[j], allowSkipped = true) }
        if (swapIndex != null) {
            val moved = result.removeAt(swapIndex)
            result.add(i, moved)
        }
    }
    return result
}

/** Whether [candidate] would be an acceptable fit at [index] in [list] - checked against the
 * list's own contents at positions < index (which a forward-only swap search never disturbs), not
 * against whatever is currently sitting at index itself. */
private fun fitsAt(list: List<Track>, index: Int, candidate: Track, allowSkipped: Boolean): Boolean {
    val windowStart = (index - ARTIST_SPACING_WINDOW).coerceAtLeast(0)
    val sameArtistNearby = candidate.artistId != null &&
        (windowStart until index).any { list[it].artistId == candidate.artistId }
    if (sameArtistNearby) return false

    val previous = list.getOrNull(index - 1)
    if (previous != null) {
        if (candidate.albumId != null && candidate.albumId == previous.albumId) return false
        val bpmA = previous.bpm
        val bpmB = candidate.bpm
        if (bpmA != null && bpmB != null && abs(bpmA - bpmB) > MAX_ADJACENT_BPM_DIFF) return false
    }
    if (!allowSkipped && candidate.skipCount >= SKIP_COUNT_THRESHOLD) return false
    return true
}
