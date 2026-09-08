package dev.nami.player.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadCoefficientsTest {

    @Test
    fun `zero gain is the identity filter`() {
        val c = BiquadCoefficients.peaking(sampleRateHz = 44100, freqHz = 1000f, gainDb = 0f, q = 0.7f)
        assertEquals(BiquadCoefficients.IDENTITY, c)
    }

    @Test
    fun `a boosted peaking filter raises a steady tone at its center frequency`() {
        val sampleRate = 44100
        val freq = 1000f
        val coeffs = BiquadCoefficients.peaking(sampleRate, freq, gainDb = 6f, q = 0.7f)
        val state = BiquadState()

        // Feed enough cycles of the filter's own center frequency for the transient to settle,
        // then compare steady-state output amplitude to input amplitude - a +6dB peaking filter
        // at its own center should land close to a factor of 2x (dBToLinear(6) ~= 1.995).
        val samples = 4000
        var maxIn = 0f
        var maxOut = 0f
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            val input = kotlin.math.sin(2.0 * Math.PI * freq * t).toFloat()
            val output = state.process(input, coeffs)
            if (i > samples / 2) { // only measure after the filter has settled
                maxIn = maxOf(maxIn, kotlin.math.abs(input))
                maxOut = maxOf(maxOut, kotlin.math.abs(output))
            }
        }
        val ratio = maxOut / maxIn
        assertTrue(ratio in 1.8f..2.2f, "expected ~2x gain at center frequency, got ${ratio}x")
    }
}
