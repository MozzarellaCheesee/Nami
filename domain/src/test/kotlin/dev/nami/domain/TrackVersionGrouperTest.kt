package dev.nami.domain

import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

private fun fixture(id: String, title: String, artistId: String? = "a1") = Track(
    id = TrackId(id), title = title, artistId = artistId?.let(::ArtistId), albumId = null,
    durationMs = 1000, path = "/music/$id.flac", format = "flac", sizeBytes = 1, dateAdded = 0,
)

class TrackVersionGrouperTest {

    @Test
    fun `groups a remix and live version with the original`() {
        val tracks = listOf(
            fixture("t1", "Window View"),
            fixture("t2", "Window View (Remix)"),
            fixture("t3", "Window View - Live"),
        )

        val groups = TrackVersionGrouper.group(tracks)

        assertEquals(1, groups.size)
        assertEquals(listOf("t1", "t2", "t3"), groups[0].map { it.id.value })
    }

    @Test
    fun `different artists with the same title stay separate`() {
        val tracks = listOf(fixture("t1", "Song", artistId = "a1"), fixture("t2", "Song", artistId = "a2"))

        val groups = TrackVersionGrouper.group(tracks)

        assertEquals(2, groups.size)
    }

    @Test
    fun `unrelated tracks each get their own singleton group`() {
        val tracks = listOf(fixture("t1", "Alpha"), fixture("t2", "Beta"))

        val groups = TrackVersionGrouper.group(tracks)

        assertEquals(listOf(listOf("t1"), listOf("t2")), groups.map { g -> g.map { it.id.value } })
    }
}
