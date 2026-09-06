package dev.nami.domain

import dev.nami.core.model.Lyrics
import dev.nami.core.model.WordTiming

/** Buckets Whisper's own (globally-timed, whole-track) word list back onto each [LyricLine] by
 * time range rather than by matching text -- Whisper's transcription doesn't reliably match the
 * LRC's own text (different romanization, mistranscribed words, etc), but both are real
 * timestamps against the same audio, so "which line was the current one when this word was
 * sung" is a simple range check and never needs the text to agree. */
object WordTimingMatcher {
    fun match(lyrics: Lyrics, whisperWords: List<WordTiming>): List<List<WordTiming>> =
        lyrics.lines.mapIndexed { index, line ->
            val rangeEnd = lyrics.lines.getOrNull(index + 1)?.timeMs ?: Long.MAX_VALUE
            whisperWords.filter { it.startMs >= line.timeMs && it.startMs < rangeEnd }
        }
}
