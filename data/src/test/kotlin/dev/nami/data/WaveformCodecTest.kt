package dev.nami.data

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaveformCodecTest {

    @Test
    fun `round trip keeps the values`() {
        val bars = listOf(0f, 0.125f, 0.5f, 1f)
        assertEquals(bars, decodeWaveform(encodeWaveform(bars)))
    }

    @Test
    fun `russian locale does not turn the separator into a decimal comma`() {
        // "%.3f" без Locale.ROOT на русской локали пишет "0,125", и разделитель значений
        // становится неотличим от дробной части: 120 значений превращались бы в 240.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            val bars = listOf(0.125f, 0.5f)
            assertEquals(bars, decodeWaveform(encodeWaveform(bars)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `null and empty mean different things`() {
        // null - ещё не считали, пустой список - считали и не вышло. Второй случай обязан
        // сохраняться, иначе трек будет заново декодироваться при каждом запуске.
        assertNull(decodeWaveform(null))
        assertEquals(emptyList(), decodeWaveform(""))
    }
}
