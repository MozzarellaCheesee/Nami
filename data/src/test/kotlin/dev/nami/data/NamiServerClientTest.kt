package dev.nami.data

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Robolectric: `org.json` под plain-JVM unit-тестом заглушён и бросает "not mocked"
// (та же причина, по которой LrcLibClient не покрыт обычным тестом).
@RunWith(RobolectricTestRunner::class)
class NamiServerClientTest {

    @Test
    fun `parseLyrics reads synced lines`() {
        val json = """
            {"track_id":7,"source":"lrclib","synced":true,
             "lines":[{"time_ms":0,"text":"one"},{"time_ms":12340,"text":"two"}],
             "fetched_at":1}
        """.trimIndent()
        val lyrics = NamiServerClient.parseLyrics(json)!!
        assertEquals(2, lyrics.lines.size)
        assertEquals(0L, lyrics.lines[0].timeMs)
        assertEquals("two", lyrics.lines[1].text)
        assertEquals(12340L, lyrics.lines[1].timeMs)
    }

    @Test
    fun `parseLyrics keeps unsynced lines with zero time`() {
        val json = """{"lines":[{"text":"plain line"}]}"""
        val lyrics = NamiServerClient.parseLyrics(json)!!
        assertEquals(1, lyrics.lines.size)
        assertEquals(0L, lyrics.lines[0].timeMs)
        assertEquals("plain line", lyrics.lines[0].text)
    }

    @Test
    fun `parseLyrics returns null for empty or malformed`() {
        assertNull(NamiServerClient.parseLyrics("""{"track_id":7,"source":"none","lines":[]}"""))
        assertNull(NamiServerClient.parseLyrics("not json"))
        assertNull(NamiServerClient.parseLyrics("""{"no_lines":true}"""))
    }
}
