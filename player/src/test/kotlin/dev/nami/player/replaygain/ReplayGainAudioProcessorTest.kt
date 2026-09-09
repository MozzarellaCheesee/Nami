package dev.nami.player.replaygain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayGainAudioProcessorTest {

    @Test
    fun `null gain is unity`() {
        assertEquals(1f, ReplayGainAudioProcessor.dbToLinear(null))
    }

    @Test
    fun `plus 6dB is roughly a 2x multiply`() {
        val linear = ReplayGainAudioProcessor.dbToLinear(6f)
        assertTrue(linear in 1.9f..2.1f)
    }

    @Test
    fun `minus 6dB is roughly a half multiply`() {
        val linear = ReplayGainAudioProcessor.dbToLinear(-6f)
        assertTrue(linear in 0.49f..0.51f)
    }

    /** Гэплесс: на границе треков конвейер не перестраивается, поэтому смена ReplayGain соседних
     * треков без сглаживания была бы ступенькой в один отсчёт - щелчком. */
    @Test
    fun `gain ramp does not step on a track boundary`() {
        val ramp = GainRamp()
        ramp.configure(sampleRate = 48000, initialGain = 1f)
        val first = ramp.nextFrameGain(2f)
        assertTrue(first - 1f < 0.01f, "первый кадр после смены не должен прыгать, получили $first")
        // 20 мс постоянной времени - за 100 мс (5 постоянных) должно почти дойти.
        var g = first
        repeat(4800) { g = ramp.nextFrameGain(2f) }
        assertTrue(g > 1.98f, "за 100 мс должно дойти до цели, получили $g")
    }

    @Test
    fun `snapTo is instant`() {
        val ramp = GainRamp()
        ramp.configure(sampleRate = 48000, initialGain = 1f)
        ramp.snapTo(0.5f)
        assertTrue(kotlin.math.abs(ramp.nextFrameGain(0.5f) - 0.5f) < 1e-6f)
    }
}
