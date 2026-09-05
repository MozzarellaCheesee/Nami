package dev.nami.player

import dev.nami.core.model.TrackId
import dev.nami.domain.QueueOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueBuilderTest {

    @Test
    fun `no now playing and empty upcoming yields empty queue`() {
        val queue = buildPlayerQueue(nowPlaying = null, upcoming = emptyList(), originByMediaId = emptyMap())

        assertNull(queue.nowPlaying)
        assertEquals(emptyList(), queue.upcoming)
    }

    @Test
    fun `now playing maps to QueueTrack with real id and metadata`() {
        val queue = buildPlayerQueue(
            nowPlaying = MediaItemInfo(mediaId = "t1", title = "Window View", artist = "Farewell225"),
            upcoming = emptyList(),
            originByMediaId = emptyMap(),
        )

        assertEquals(TrackId("t1"), queue.nowPlaying?.id)
        assertEquals("Window View", queue.nowPlaying?.title)
        assertEquals("Farewell225", queue.nowPlaying?.artistName)
    }

    @Test
    fun `now playing artworkPath is propagated to QueueTrack`() {
        val queue = buildPlayerQueue(
            nowPlaying = MediaItemInfo(mediaId = "t1", title = "Window View", artist = "Farewell225", artworkPath = "art/t1.jpg"),
            upcoming = emptyList(),
            originByMediaId = emptyMap(),
        )

        assertEquals("art/t1.jpg", queue.nowPlaying?.artworkPath)
    }

    @Test
    fun `upcoming items default to CONTEXT origin when absent from the map`() {
        val queue = buildPlayerQueue(
            nowPlaying = null,
            upcoming = listOf(MediaItemInfo("t2", "Nocturne", "sasakure.UK")),
            originByMediaId = emptyMap(),
        )

        assertEquals(QueueOrigin.CONTEXT, queue.upcoming[0].origin)
    }

    @Test
    fun `upcoming items use MANUAL origin when present in the map`() {
        val queue = buildPlayerQueue(
            nowPlaying = null,
            upcoming = listOf(MediaItemInfo("t2", "Nocturne", "sasakure.UK")),
            originByMediaId = mapOf("t2" to QueueOrigin.MANUAL),
        )

        assertEquals(QueueOrigin.MANUAL, queue.upcoming[0].origin)
    }
}
