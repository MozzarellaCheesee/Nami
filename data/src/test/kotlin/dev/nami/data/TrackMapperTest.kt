package dev.nami.data

import dev.nami.core.database.entity.TrackEntity
import dev.nami.data.mapper.toDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackMapperTest {
    @Test
    fun `entity maps to domain track preserving id and path`() {
        val entity = TrackEntity(
            id = "t1", title = "Window View", artistId = null, albumId = null,
            trackNo = null, discNo = null, durationMs = 180_000,
            path = "/data/music/t1.flac", format = "flac", sizeBytes = 40_000_000,
            dateAdded = 1000L, lastPlayed = null, playCount = 0,
        )

        val track = entity.toDomain()

        assertEquals("t1", track.id.value)
        assertEquals("/data/music/t1.flac", track.path)
        assertEquals(180_000L, track.durationMs)
    }
}
