package dev.nami.player.dsd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Builds a minimal, spec-shaped synthetic DSF byte buffer (2 channels, tiny block size) rather
 * than shipping a real fixture -- this is testing the parser's byte-layout logic, not decoding an
 * actual recording. */
private fun buildDsf(
    channelNum: Int = 2,
    sampleRateHz: Int = 2822400,
    blockSizePerChannel: Int = 4,
    bitsPerSample: Int = 1,
    channelBlocks: List<ByteArray>, // one block-sized ByteArray per channel per "round"
): ByteArray {
    val sampleCount = blockSizePerChannel.toLong() * 8 // bits per channel, one round
    val out = ByteArrayOutputStream()
    fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    fun writeIntLe(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    fun writeLongLe(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())

    writeAscii("DSD ")
    writeLongLe(28)
    writeLongLe(0) // file size, unused by parser
    writeLongLe(0) // metadata pointer, unused

    writeAscii("fmt ")
    writeLongLe(52)
    writeIntLe(1) // format version
    writeIntLe(0) // format id
    writeIntLe(if (channelNum == 2) 2 else 1) // channel type (not read by parser)
    writeIntLe(channelNum)
    writeIntLe(sampleRateHz)
    writeIntLe(bitsPerSample)
    writeLongLe(sampleCount)
    writeIntLe(blockSizePerChannel)
    writeIntLe(0) // reserved

    val dataSize = channelBlocks.sumOf { it.size }.toLong() + 12
    writeAscii("data")
    writeLongLe(dataSize)
    channelBlocks.forEach { out.write(it) }

    return out.toByteArray()
}

class DsfParserTest {

    @Test
    fun `parses a well-formed stereo DSF and de-interleaves channel blocks`() {
        val ch0Block = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val ch1Block = byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte())
        val dsf = buildDsf(channelNum = 2, blockSizePerChannel = 4, channelBlocks = listOf(ch0Block, ch1Block))

        val audio = DsfParser.parse(dsf)

        assertEquals(2822400, audio?.sampleRateHz)
        assertEquals(2, audio?.channelCount)
        assertContentEquals(ch0Block, audio?.dsdBytesPerChannel?.get(0))
        assertContentEquals(ch1Block, audio?.dsdBytesPerChannel?.get(1))
    }

    @Test
    fun `rejects a non-DSF file`() {
        assertNull(DsfParser.parse(ByteArray(200)))
    }

    @Test
    fun `rejects unsupported bits-per-sample`() {
        val dsf = buildDsf(bitsPerSample = 8, channelBlocks = listOf(ByteArray(4), ByteArray(4)))
        assertNull(DsfParser.parse(dsf))
    }
}
