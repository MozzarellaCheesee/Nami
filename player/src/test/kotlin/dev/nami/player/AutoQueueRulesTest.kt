package dev.nami.player

import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun fixture(
    id: String,
    artistId: String? = null,
    albumId: String? = null,
    skipCount: Int = 0,
    bpm: Float? = null,
) = Track(
    id = TrackId(id),
    title = id,
    artistId = artistId?.let(::ArtistId),
    albumId = albumId?.let(::AlbumId),
    durationMs = 180_000,
    path = "/music/$id.flac",
    format = "flac",
    sizeBytes = 1,
    dateAdded = 0,
    skipCount = skipCount,
    bpm = bpm,
)

class AutoQueueRulesTest {

    @Test
    fun `too short a list is returned unchanged`() {
        val single = listOf(fixture("t1"))
        assertEquals(single, applyAutoQueueRules(single))
    }

    @Test
    fun `separates the same artist when a later swap can fix it`() {
        val tracks = listOf(
            fixture("t1", artistId = "a1"),
            fixture("t2", artistId = "a1"),
            fixture("t3", artistId = "a2"),
        )

        val result = applyAutoQueueRules(tracks)

        // t2 (same artist as t1) should have been swapped with t3.
        assertEquals(listOf("t1", "t3", "t2"), result.map { it.id.value })
    }

    @Test
    fun `leaves the order alone when no swap can fix a violation`() {
        val tracks = listOf(
            fixture("t1", artistId = "a1"),
            fixture("t2", artistId = "a1"),
        )

        val result = applyAutoQueueRules(tracks)

        // Nothing else exists to swap in - the violation is left rather than losing the track.
        assertEquals(listOf("t1", "t2"), result.map { it.id.value })
    }

    @Test
    fun `avoids two consecutive tracks from the same album`() {
        val tracks = listOf(
            fixture("t1", albumId = "al1"),
            fixture("t2", albumId = "al1"),
            fixture("t3", albumId = "al2"),
        )

        val result = applyAutoQueueRules(tracks)

        assertEquals(listOf("t1", "t3", "t2"), result.map { it.id.value })
    }

    @Test
    fun `avoids a large BPM jump between adjacent tracks when a closer match exists later`() {
        val tracks = listOf(
            fixture("t1", bpm = 120f),
            fixture("t2", bpm = 200f),
            fixture("t3", bpm = 125f),
        )

        val result = applyAutoQueueRules(tracks)

        assertEquals(listOf("t1", "t3", "t2"), result.map { it.id.value })
    }

    @Test
    fun `unscanned bpm never triggers the bpm rule`() {
        val tracks = listOf(fixture("t1", bpm = 120f), fixture("t2", bpm = null))
        assertEquals(listOf("t1", "t2"), applyAutoQueueRules(tracks).map { it.id.value })
    }

    @Test
    fun `heavily skipped track still plays when nothing else can take its place`() {
        val tracks = listOf(fixture("t1"), fixture("t2", skipCount = 5))
        val result = applyAutoQueueRules(tracks)
        assertTrue(result.map { it.id.value }.contains("t2"))
    }
}
