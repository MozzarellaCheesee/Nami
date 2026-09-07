package dev.nami.data

// Most J-pop .lrc lyrics carry NO sentence-ending punctuation at all (no 。！？), so "keep
// scanning forward until you see one" (an earlier version of this) never finds one and swallows
// the entire song into a single group -- one translation gets stamped onto every line, which is
// worse than the original per-line-fragment problem it was meant to fix. A trailing 、 (comma) is
// the one signal that's actually reliable and deliberate: it means the vocal phrase continues on
// the next line. Bounded to that -- never scans past a line without one.
private const val CONTINUATION_MARK = "、"

/** Groups a lyric line with the next one(s) only when it ends in [CONTINUATION_MARK] -- a real,
 * deliberate "this sentence continues" signal, not an absence-of-punctuation guess. */
object SentenceGrouper {
    data class Group(val text: String, val indices: List<Int>)

    fun group(lines: List<String>): List<Group> {
        val groups = mutableListOf<Group>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank()) {
                groups.add(Group(lines[i], listOf(i)))
                i++
                continue
            }
            val indices = mutableListOf(i)
            var j = i
            while (lines[j].trim().endsWith(CONTINUATION_MARK) && j + 1 < lines.size && lines[j + 1].isNotBlank()) {
                j++
                indices.add(j)
            }
            groups.add(Group(indices.joinToString(" ") { lines[it] }, indices))
            i = j + 1
        }
        return groups
    }
}
