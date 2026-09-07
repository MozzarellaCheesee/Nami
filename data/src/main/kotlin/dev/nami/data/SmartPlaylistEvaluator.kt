package dev.nami.data

import dev.nami.core.model.Track
import dev.nami.domain.SmartField
import dev.nami.domain.SmartOperator
import dev.nami.domain.SmartQuery
import dev.nami.domain.SmartRule
import dev.nami.domain.SmartSortField
import java.io.File

/** Evaluates a [SmartQuery] against the whole library in plain Kotlin (not translated to SQL) --
 * the rule set is small and the field list mixes a DB column (genre), a derived value (days since
 * a timestamp) and a filesystem check (HAS_LYRICS), so a single WHERE-clause builder would need
 * three different query strategies anyway. All rules AND together -- the plan's own examples
 * ("жанр = X И playCount > 5 И добавлено за 30 дней") never show OR, so this doesn't build a
 * combinator UI has no use for. */
object SmartPlaylistEvaluator {
    fun evaluate(tracks: List<Track>, query: SmartQuery): List<Track> {
        val now = System.currentTimeMillis()
        val filtered = tracks.filter { track -> query.rules.all { rule -> matches(track, rule, now) } }
        val sorted = when (query.sortBy) {
            SmartSortField.DATE_ADDED -> filtered.sortedBy { it.dateAdded }
            SmartSortField.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SmartSortField.PLAY_COUNT -> filtered.sortedBy { it.playCount }
            SmartSortField.DURATION -> filtered.sortedBy { it.durationMs }
        }
        val ordered = if (query.sortDescending) sorted.asReversed() else sorted
        return query.limit?.let { ordered.take(it) } ?: ordered
    }

    private fun matches(track: Track, rule: SmartRule, nowMs: Long): Boolean = when (rule.field) {
        SmartField.GENRE -> compareStrings(track.genre, rule.operator, rule.value)
        SmartField.FORMAT -> compareStrings(track.format, rule.operator, rule.value)
        SmartField.PLAY_COUNT -> compareNumbers(track.playCount.toDouble(), rule.operator, rule.value)
        SmartField.DURATION_SEC -> compareNumbers((track.durationMs / 1000).toDouble(), rule.operator, rule.value)
        SmartField.ADDED_DAYS_AGO -> compareNumbers(daysSince(track.dateAdded, nowMs), rule.operator, rule.value)
        SmartField.LAST_PLAYED_DAYS_AGO -> {
            // Never-played reads as "infinitely long ago" -- so "не играло 180+ дней" also
            // catches tracks that have literally never played, which is the intent of a
            // "забытое"-style preset, not an edge case to special-case around.
            val days = track.lastPlayed?.let { daysSince(it, nowMs) } ?: Double.MAX_VALUE
            compareNumbers(days, rule.operator, rule.value)
        }
        SmartField.HAS_LYRICS -> {
            val hasLyrics = lrcFileFor(track.path).exists()
            val expected = rule.value.toBooleanStrictOrNull() ?: true
            if (rule.operator == SmartOperator.NOT_EQUALS) hasLyrics != expected else hasLyrics == expected
        }
    }

    private fun daysSince(timestampMs: Long, nowMs: Long): Double = (nowMs - timestampMs) / 86_400_000.0

    private fun lrcFileFor(path: String): File {
        val dot = path.lastIndexOf('.')
        val base = if (dot > path.lastIndexOf('/')) path.substring(0, dot) else path
        return File("$base.lrc")
    }

    private fun compareStrings(actual: String?, operator: SmartOperator, expected: String): Boolean {
        val matches = actual?.equals(expected, ignoreCase = true) == true
        return if (operator == SmartOperator.NOT_EQUALS) !matches else matches
    }

    private fun compareNumbers(actual: Double, operator: SmartOperator, expectedRaw: String): Boolean {
        val expected = expectedRaw.toDoubleOrNull() ?: return false
        return when (operator) {
            SmartOperator.EQUALS -> actual == expected
            SmartOperator.NOT_EQUALS -> actual != expected
            SmartOperator.GREATER_THAN -> actual > expected
            SmartOperator.LESS_THAN -> actual < expected
        }
    }
}
