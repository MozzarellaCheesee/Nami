package dev.nami.domain

import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadioBuilderTest {
    private fun track(id: String, artistId: String? = null, genre: String? = null, bpm: Float? = null) = Track(
        id = TrackId(id),
        title = id,
        artistId = artistId?.let(::ArtistId),
        albumId = null as AlbumId?,
        durationMs = 180_000,
        path = "/music/$id.mp3",
        format = "mp3",
        sizeBytes = 1,
        dateAdded = 0,
        genre = genre,
        bpm = bpm,
    )

    @Test
    fun `seed track is always first`() {
        val seed = track("seed", artistId = "a1")
        val library = listOf(seed, track("t1"), track("t2"))
        val queue = RadioBuilder.build(seed, library, Random(0))
        assertEquals(seed.id, queue.first().id)
    }

    @Test
    fun `never duplicates a track and never exceeds library size`() {
        val seed = track("seed")
        val library = listOf(seed) + (1..10).map { track("t$it") }
        val queue = RadioBuilder.build(seed, library, Random(42))
        assertEquals(queue.size, queue.map { it.id }.toSet().size)
        assertTrue(queue.size <= library.size)
    }

    @Test
    fun `single-track library returns just the seed`() {
        val seed = track("seed")
        assertEquals(listOf(seed), RadioBuilder.build(seed, listOf(seed), Random(1)))
    }
}
