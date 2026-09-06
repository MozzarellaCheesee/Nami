package dev.nami.core.model

/** Word-level timing from the on-device Whisper forced-alignment pass, one entry per lyric word,
 * in playback order across the whole track (not grouped by line -- [LyricsRepository] matches
 * these back onto [LyricLine]s by word position, since Whisper's own transcription text doesn't
 * always match the LRC text verbatim). */
data class WordTiming(val word: String, val startMs: Long, val endMs: Long)
