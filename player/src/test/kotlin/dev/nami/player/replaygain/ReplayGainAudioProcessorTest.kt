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
}
