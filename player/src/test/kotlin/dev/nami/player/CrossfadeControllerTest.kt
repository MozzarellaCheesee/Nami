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
        // Equal-power curve: sin(0.5 * pi/2) ~= 0.707, not the 0.5 a linear ramp would give.
        val mid = CrossfadeController.volumeFor(positionMs = CrossfadeController.FADE_MS / 2, durationMs = 180_000)
        assert(mid in 0.7f..0.71f) { "expected ~0.707, got $mid" }
    }

    @Test
    fun `just before track end ramps down to zero`() {
        val duration = 180_000L
        assertEquals(0f, CrossfadeController.volumeFor(positionMs = duration, durationMs = duration))
        val mid = CrossfadeController.volumeFor(positionMs = duration - CrossfadeController.FADE_MS / 2, durationMs = duration)
        assert(mid in 0.7f..0.71f) { "expected ~0.707, got $mid" }
    }

    @Test
    fun `short track fades in and out without going negative`() {
        val duration = 1000L
        val volume = CrossfadeController.volumeFor(positionMs = 500, durationMs = duration)
        assert(volume in 0f..1f)
    }

    @Test
    fun `crossfade pair starts and ends fully handed over`() {
        assertEquals(0f, CrossfadeController.fadeIn(0f))
        assertEquals(1f, CrossfadeController.fadeOut(0f))
        assertEquals(1f, CrossfadeController.fadeIn(1f))
        assert(CrossfadeController.fadeOut(1f) < 1e-6f) { "outgoing must reach silence" }
    }

    @Test
    fun `crossfade pair is equal-power across the whole overlap`() {
        // The point of the sin/cos pair: summed POWER stays 1 through the overlap, so the two
        // tracks playing at once don't sag ~3dB in the middle the way a linear pair does.
        for (step in 0..20) {
            val t = step / 20f
            val inVol = CrossfadeController.fadeIn(t)
            val outVol = CrossfadeController.fadeOut(t)
            val power = inVol * inVol + outVol * outVol
            assert(power in 0.999f..1.001f) { "power at t=$t was $power" }
        }
    }

    @Test
    fun `crossfade progress is clamped outside the fade window`() {
        assertEquals(0f, CrossfadeController.fadeIn(-1f))
        assertEquals(1f, CrossfadeController.fadeIn(5f))
    }
}
