package dev.nami.player

import kotlin.test.Test
import kotlin.test.assertEquals

class CrossfadeControllerTest {

    @Test
    fun `unknown duration is always full volume`() {
        assertEquals(1f, CrossfadeController.volumeFor(positionMs = 1000, durationMs = 0))
    }

    @Test
    fun `mid track is full volume`() {
        assertEquals(1f, CrossfadeController.volumeFor(positionMs = 60_000, durationMs = 180_000))
    }

    @Test
    fun `just after track start ramps up from zero`() {
        assertEquals(0f, CrossfadeController.volumeFor(positionMs = 0, durationMs = 180_000))
        assertEquals(0.5f, CrossfadeController.volumeFor(positionMs = CrossfadeController.FADE_MS / 2, durationMs = 180_000))
    }

    @Test
    fun `just before track end ramps down to zero`() {
        val duration = 180_000L
        assertEquals(0f, CrossfadeController.volumeFor(positionMs = duration, durationMs = duration))
        assertEquals(0.5f, CrossfadeController.volumeFor(positionMs = duration - CrossfadeController.FADE_MS / 2, durationMs = duration))
    }

    @Test
    fun `short track fades in and out without going negative`() {
        val duration = 1000L
        val volume = CrossfadeController.volumeFor(positionMs = 500, durationMs = duration)
        assert(volume in 0f..1f)
    }
}
