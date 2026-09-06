package dev.nami.core.model

/** One synced line: [timeMs] is when it starts playing, relative to the track. Word-level
 * ("enhanced LRC") timing isn't modeled yet -- karaoke-style per-character highlight is a later
 * pass, this is line-level only. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>)
