package dev.nami.data

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.SmartField
import dev.nami.domain.SmartOperator
import dev.nami.domain.SmartQuery
import dev.nami.domain.SmartRule
import dev.nami.domain.SmartSortField
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun fixture(
    id: String,
    genre: String? = null,
    format: String = "flac",
    playCount: Int = 0,
    dateAdded: Long = 0,
    lastPlayed: Long? = null,
    durationMs: Long = 180_000,
) = Track(
    id = TrackId(id), title = id, artistId = null, albumId = null,
    genre = genre, format = format, playCount = playCount, dateAdded = dateAdded,
    lastPlayed = lastPlayed, durationMs = durationMs,
    path = "/music/$id.$format", sizeBytes = 1,
)

@RunWith(RobolectricTestRunner::class)
class SmartPlaylistEvaluatorTest {

    @Test
    fun `filters by genre equals`() {
        val tracks = listOf(fixture("t1", genre = "Touhou"), fixture("t2", genre = "Jazz"))
        val query = SmartQuery(rules = listOf(SmartRule(SmartField.GENRE, SmartOperator.EQUALS, "touhou")))

        val result = SmartPlaylistEvaluator.evaluate(tracks, query)

        assertEquals(listOf("t1"), result.map { it.id.value })
    }

    @Test
    fun `combines rules with AND`() {
        val tracks = listOf(
            fixture("t1", genre = "Touhou", playCount = 10),
            fixture("t2", genre = "Touhou", playCount = 0),
            fixture("t3", genre = "Jazz", playCount = 10),
        )
        val query = SmartQuery(
            rules = listOf(
                SmartRule(SmartField.GENRE, SmartOperator.EQUALS, "Touhou"),
                SmartRule(SmartField.PLAY_COUNT, SmartOperator.GREATER_THAN, "5"),
            ),
        )

        val result = SmartPlaylistEvaluator.evaluate(tracks, query)

        assertEquals(listOf("t1"), result.map { it.id.value })
    }

    @Test
    fun `never-played counts as infinitely long ago`() {
        val now = System.currentTimeMillis()
        val tracks = listOf(
            fixture("never", lastPlayed = null),
            fixture("recent", lastPlayed = now),
        )
        val query = SmartQuery(rules = listOf(SmartRule(SmartField.LAST_PLAYED_DAYS_AGO, SmartOperator.GREATER_THAN, "180")))

        val result = SmartPlaylistEvaluator.evaluate(tracks, query)

        assertEquals(listOf("never"), result.map { it.id.value })
    }

    @Test
    fun `sorts descending by play count and applies limit`() {
        val tracks = listOf(fixture("low", playCount = 1), fixture("high", playCount = 9), fixture("mid", playCount = 5))
        val query = SmartQuery(rules = emptyList(), sortBy = SmartSortField.PLAY_COUNT, sortDescending = true, limit = 2)

        val result = SmartPlaylistEvaluator.evaluate(tracks, query)

        assertEquals(listOf("high", "mid"), result.map { it.id.value })
    }

    @Test
    fun `serializer round-trips a query`() {
        val query = SmartQuery(
            rules = listOf(SmartRule(SmartField.FORMAT, SmartOperator.EQUALS, "flac")),
            sortBy = SmartSortField.TITLE,
            sortDescending = false,
            limit = 100,
        )

        val restored = SmartQuerySerializer.parse(SmartQuerySerializer.serialize(query))

        assertEquals(query, restored)
    }
}
