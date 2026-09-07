package dev.nami.domain

import dev.nami.core.model.Track

/** П.md §23.21 "Группировка версий" -- collapses `original / remix / live / instrumental /
 * acoustic` of the same track into one row with the rest tucked behind an expand toggle. Pure
 * name-matching heuristic (strips a trailing "(Remix)"/"- Live"/etc. and compares what's left,
 * same artist) -- there's no audio-similarity check backing this, two different tracks that
 * happen to share a stripped title will group together too. */
object TrackVersionGrouper {
    private val VERSION_SUFFIX = Regex(
        """[\s]*[-(]\s*(remix|live|instrumental|acoustic|акустика|инструментал|ремикс|лайв)[^)]*\)?\s*${'$'}""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns groups in first-occurrence order (the order [tracks] arrives in), each group's own
     * tracks also keeping that relative order. A group of size 1 just means no other version of
     * that track exists in [tracks] -- callers render those exactly like before, no badge. */
    fun group(tracks: List<Track>): List<List<Track>> {
        val order = LinkedHashMap<Pair<String, Any?>, MutableList<Track>>()
        for (track in tracks) {
            val key = baseTitle(track.title) to track.artistId
            order.getOrPut(key) { mutableListOf() }.add(track)
        }
        return order.values.toList()
    }

    private fun baseTitle(title: String): String = title.replace(VERSION_SUFFIX, "").trim().lowercase()
}
