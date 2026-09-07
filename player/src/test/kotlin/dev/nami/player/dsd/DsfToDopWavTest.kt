package dev.nami.player.dsd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun buildMonoDsf(sampleRateHz: Int, blockSizePerChannel: Int, dsdBytes: ByteArray): ByteArray {
    val sampleCount = dsdBytes.size.toLong() * 8
    val out = ByteArrayOutputStream()
    fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    fun writeIntLe(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    fun writeLongLe(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())

    writeAscii("DSD "); writeLongLe(28); writeLongLe(0); writeLongLe(0)
    writeAscii("fmt "); writeLongLe(52)
    writeIntLe(1); writeIntLe(0); writeIntLe(0); writeIntLe(1)
    writeIntLe(sampleRateHz); writeIntLe(1)
    writeLongLe(sampleCount); writeIntLe(blockSizePerChannel); writeIntLe(0)
    writeAscii("data"); writeLongLe(dsdBytes.size.toLong() + 12)
    out.write(dsdBytes)
    return out.toByteArray()
}

class DsfToDopWavTest {

    @Test
    fun `produces a valid 24-bit PCM wav header for the DoP-packed stream`() {
        val dsdBytes = ByteArray(8) { it.toByte() } // 4 DoP frames worth (2 bytes each)
        val dsf = buildMonoDsf(sampleRateHz = 2822400, blockSizePerChannel = 8, dsdBytes = dsdBytes)

        val wav = DsfToDopWav.convert(dsf)

        requireNotNull(wav)
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, buf.getShort(20).toInt()) // PCM format code
        assertEquals(1, buf.getShort(22).toInt()) // mono
        assertEquals(2822400 / 16, buf.getInt(24)) // DoP sample rate = DSD rate / 16
        assertEquals(24, buf.getShort(34).toInt()) // bits per sample
        val dataSize = buf.getInt(40)
        assertEquals(4 * 3, dataSize) // 4 DoP frames * 3 bytes each
    }

    @Test
    fun `null for a file that isn't a DSF`() {
        assertNull(DsfToDopWav.convert(ByteArray(100)))
    }
}
