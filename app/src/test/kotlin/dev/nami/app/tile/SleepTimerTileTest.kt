package dev.nami.app.tile

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepTimerTileTest {

    @Test
    fun `остаток округляется вверх до минуты`() {
        assertEquals("30 мин", formatRemaining(30 * 60_000L))
        // Главное, ради чего округление вверх: "1 мин" держится до самого нуля, плитка не
        // показывает "0 мин" пока таймер ещё идёт.
        assertEquals("1 мин", formatRemaining(1L))
        assertEquals("1 мин", formatRemaining(60_000L))
        assertEquals("2 мин", formatRemaining(60_001L))
        assertEquals("0 мин", formatRemaining(0L))
    }
}
