package dev.nami.data

// A lyric line that doesn't end on one of these usually isn't a complete sentence -- whoever
// timed the .lrc file split it mid-clause across two lines (common in J-pop, the vocal phrasing
// doesn't line up with sentence grammar). Translating that fragment alone is what makes a
// translator (MLKit or DeepL, either one) come out "кривой" -- no cross-line context, so it
// either drops the dangling clause or invents a subject/verb to complete it.
private val SENTENCE_END = Regex("[。！？…!?]+[」』）)]*$")

/** Groups lyric lines that together form one sentence, so a translator sees the whole sentence
 * at once instead of an arbitrary fragment. */
object SentenceGrouper {
    data class Group(val text: String, val indices: List<Int>)

    fun group(lines: List<String>): List<Group> {
        val groups = mutableListOf<Group>()
        var groupStart = 0
        for (i in lines.indices) {
            val endsGroup = lines[i].isBlank() || SENTENCE_END.containsMatchIn(lines[i]) || i == lines.lastIndex
            if (!endsGroup) continue
            val nonBlank = (groupStart..i).filterNot { lines[it].isBlank() }
            if (nonBlank.isNotEmpty()) groups.add(Group(nonBlank.joinToString(" ") { lines[it] }, nonBlank))
            groupStart = i + 1
        }
        return groups
    }
}
