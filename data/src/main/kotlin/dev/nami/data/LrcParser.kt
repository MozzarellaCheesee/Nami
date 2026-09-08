package dev.nami.data

import dev.nami.core.model.LyricLine
import dev.nami.core.model.Lyrics

/** Standard LRC (`[mm:ss.xx]text`), tolerant of "enhanced LRC" word-level tags
 * (`<mm:ss.xx>word`) - those are stripped rather than used, karaoke-level per-word timing is a
 * later pass. A line can carry more than one time tag (`[00:12.00][00:45.00]text`, a repeated
 * chorus) - each becomes its own line at that timestamp. */
object LrcParser {
    private val lineTimeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val wordTimeTag = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String): Lyrics {
        val lines = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { line ->
            val tags = lineTimeTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1)
                .replace(wordTimeTag, "")
                .trim()
            tags.forEach { tag -> lines.add(LyricLine(timeMs = tag.toMillis(), text = text)) }
        }
        return Lyrics(lines.sortedBy { it.timeMs })
    }

    fun format(lyrics: Lyrics): String = lyrics.lines.joinToString("\n") { line ->
        val totalMs = line.timeMs.coerceAtLeast(0)
        val minutes = totalMs / 60_000
        val seconds = (totalMs % 60_000) / 1000
        val centis = (totalMs % 1000) / 10
        "[%02d:%02d.%02d]%s".format(minutes, seconds, centis, line.text)
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]
        val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').take(3).toLong()
        return minutes * 60_000 + seconds * 1000 + millis
    }
}
