package dev.nami.player.dsd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Builds a synthetic DSDIFF (.dff) file in memory according to Philips DSDIFF spec (Big-Endian).
 */
private fun buildDff(
    sampleRateHz: Int = 2822400,
    channelCount: Int = 2,
    compressionType: String = "DSD ",
    interleavedData: ByteArray,
): ByteArray {
    val out = ByteArrayOutputStream()
    fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    fun writeIntBe(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array())
    fun writeShortBe(v: Short) = out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(v).array())
    fun writeLongBe(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array())

    // Subchunks for PROP SND
    val propSndOut = ByteArrayOutputStream()
    propSndOut.write("SND ".toByteArray(Charsets.US_ASCII))

    // FS
    propSndOut.write("FS  ".toByteArray(Charsets.US_ASCII))
    propSndOut.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(4L).array())
    propSndOut.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(sampleRateHz).array())

    // CHNL
    propSndOut.write("CHNL".toByteArray(Charsets.US_ASCII))
    propSndOut.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(2L).array())
    propSndOut.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(channelCount.toShort()).array())

    // CMPR
    propSndOut.write("CMPR".toByteArray(Charsets.US_ASCII))
    propSndOut.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(4L).array())
    propSndOut.write(compressionType.toByteArray(Charsets.US_ASCII))

    val propBytes = propSndOut.toByteArray()

    // Root chunk FRM8
    val totalSize = 4L + (12L + propBytes.size) + (12L + interleavedData.size)
    writeAscii("FRM8")
    writeLongBe(totalSize)
    writeAscii("DSD ")

    // PROP chunk
    writeAscii("PROP")
    writeLongBe(propBytes.size.toLong())
    out.write(propBytes)

    // DSD chunk
    writeAscii("DSD ")
    writeLongBe(interleavedData.size.toLong())
    out.write(interleavedData)

    return out.toByteArray()
}

class DffParserTest {

    @Test
    fun `parses a well-formed stereo DFF and de-interleaves samples`() {
        // Interleaved: ch0, ch1, ch0, ch1
        val interleaved = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val dff = buildDff(
            sampleRateHz = 2822400,
            channelCount = 2,
            compressionType = "DSD ",
            interleavedData = interleaved,
        )

        val audio = DffParser.parse(dff)
        assertNotNull(audio)
        assertEquals(2822400, audio.sampleRateHz)
        assertEquals(2, audio.channelCount)
        assertEquals(2, audio.dsdBytesPerChannel.size)
        // Ch 0 should have 0x10, 0x30
        assertContentEquals(byteArrayOf(0x10, 0x30), audio.dsdBytesPerChannel[0])
        // Ch 1 should have 0x20, 0x40
        assertContentEquals(byteArrayOf(0x20, 0x40), audio.dsdBytesPerChannel[1])
    }

    @Test
    fun `rejects a non-DFF file`() {
        assertNull(DffParser.parse(ByteArray(100)))
    }

    @Test
    fun `rejects unsupported compression type`() {
        val dff = buildDff(
            compressionType = "DST ", // DST is compressed DSD, not supported
            interleavedData = byteArrayOf(0x10, 0x20),
        )
        assertNull(DffParser.parse(dff))
    }
}
