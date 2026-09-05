package dev.nami.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackTest {
    @Test
    fun `track carries id and path unchanged`() {
        val track = Track(
            id = TrackId("t1"),
            title = "Window View",
            artistId = null,
            albumId = null,
            durationMs = 180_000,
            path = "/music/t1.flac",
            format = "flac",
            sizeBytes = 40_000_000,
            dateAdded = 0L,
        )
        assertEquals("t1", track.id.value)
        assertEquals("/music/t1.flac", track.path)
    }
}
