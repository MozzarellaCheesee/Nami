package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStateTest {
    @Test
    fun `idle state has no current track`() {
        val state = PlaybackState.Idle
        assertEquals(PlaybackState.Idle, state)
    }

    @Test
    fun `playing state carries track id and position`() {
        val state = PlaybackState.Playing(
            trackId = TrackId("t1"),
            positionMs = 42_000,
            durationMs = 180_000,
            isPlaying = true,
        )
        assertEquals(TrackId("t1"), state.trackId)
        assertEquals(42_000L, state.positionMs)
    }
}
