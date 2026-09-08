package dev.nami.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Границы включительные и уезжают в Content-Range/Content-Length - ошибка на единицу здесь
 * означает битый звук на телевизоре, а не исключение, поэтому проверяется отдельно. */
class LocalHttpServerRangeTest {
    @Test
    fun `no header means whole file`() {
        assertNull(parseByteRange(null, 1000))
    }

    @Test
    fun `open ended range runs to the last byte`() {
        assertEquals(100L..999L, parseByteRange("bytes=100-", 1000))
    }

    @Test
    fun `closed range is inclusive on both ends`() {
        assertEquals(0L..99L, parseByteRange("bytes=0-99", 1000))
    }

    @Test
    fun `end past the file is clamped`() {
        assertEquals(500L..999L, parseByteRange("bytes=500-5000", 1000))
    }

    @Test
    fun `nonsense falls back to the whole file`() {
        assertNull(parseByteRange("bytes=abc-", 1000))
        assertNull(parseByteRange("bytes=1000-", 1000)) // начало за концом файла
        assertNull(parseByteRange("bytes=200-100", 1000))
        assertNull(parseByteRange("bytes=0-99", 0))
    }
}
