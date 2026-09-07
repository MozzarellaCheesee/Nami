package dev.nami.player.dsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DopEncoderTest {

    @Test
    fun `alternates 0x05 and 0xFA markers per frame`() {
        val dsd = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val encoded = DopEncoder.encode(dsd)

        assertEquals(6, encoded.size)
        assertEquals(0x05, encoded[0].toInt() and 0xFF)
        assertEquals(0x11, encoded[1].toInt() and 0xFF)
        assertEquals(0x22, encoded[2].toInt() and 0xFF)
        assertEquals(0xFA, encoded[3].toInt() and 0xFF)
        assertEquals(0x33, encoded[4].toInt() and 0xFF)
        assertEquals(0x44, encoded[5].toInt() and 0xFF)
    }

    @Test
    fun `rejects odd-length input`() {
        assertFailsWith<IllegalArgumentException> { DopEncoder.encode(byteArrayOf(0x01)) }
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, DopEncoder.encode(ByteArray(0)).size)
    }
}
