package dev.nami.player.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoEqParserTest {

    @Test
    fun `bucket filters onto nearest fixed band and add preamp`() {
        val text = """
            Preamp: -1.0 dB
            Filter 1: ON PK Fc 70 Hz Gain 4.0 dB Q 0.90
            Filter 2: ON PK Fc 3900 Hz Gain -2.0 dB Q 1.40
        """.trimIndent()

        val gains = AutoEqParser.parse(text)

        assertEquals(9, gains?.size)
        // 63Hz band is the nearest to 70Hz -> 4.0 - 1.0 preamp
        assertEquals(3.0f, gains!![0], 0.01f)
        // 4000Hz band is the nearest to 3900Hz -> -2.0 - 1.0 preamp
        assertEquals(-3.0f, gains[6], 0.01f)
        // untouched bands only carry the preamp
        assertEquals(-1.0f, gains[3], 0.01f)
    }

    @Test
    fun `clamps to plus-minus 12dB`() {
        val text = "Filter 1: ON PK Fc 1000 Hz Gain 20.0 dB Q 0.7"
        val gains = AutoEqParser.parse(text)
        assertEquals(12f, gains!![4])
    }

    @Test
    fun `null when no filter lines match`() {
        assertNull(AutoEqParser.parse("not an AutoEQ file"))
    }
}
