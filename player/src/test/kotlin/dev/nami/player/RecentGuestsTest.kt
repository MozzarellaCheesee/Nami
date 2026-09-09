package dev.nami.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** Число слушающих у хоста считается только по недавним запросам - "отключился" в протоколе никто
 * не говорит, гость просто перестаёт спрашивать. Ошибка тут = хост врёт о числе гостей. */
class RecentGuestsTest {
    @Test
    fun `different addresses count separately`() {
        var now = 0L
        val guests = RecentGuests(windowMs = 6_000L, nowMs = { now })
        guests.seen("192.168.0.2")
        guests.seen("192.168.0.3")
        assertEquals(2, guests.count())
        now = 1_000L
        assertEquals(2, guests.count())
    }

    @Test
    fun `same address asking twice is still one guest`() {
        var now = 0L
        val guests = RecentGuests(windowMs = 6_000L, nowMs = { now })
        guests.seen("192.168.0.2")
        now = 1_500L
        guests.seen("192.168.0.2")
        assertEquals(1, guests.count())
    }

    @Test
    fun `guest silent longer than the window drops out`() {
        var now = 0L
        val guests = RecentGuests(windowMs = 6_000L, nowMs = { now })
        guests.seen("192.168.0.2")
        guests.seen("192.168.0.3")
        now = 4_000L
        guests.seen("192.168.0.2")
        now = 7_000L
        assertEquals(1, guests.count()) // .3 молчит 7 с - выпал, .2 спрашивал 3 с назад
        now = 20_000L
        assertEquals(0, guests.count())
    }
}
