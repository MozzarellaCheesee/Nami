package dev.nami.player.dsd

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parsed contents of a Sony DSF (.dsf) container - the DSIFF-based, simpler of the two common
 * DSD file formats (the other being Philips DFF, not handled here). Spec: DSD bits are stored
 * LSB-first per byte, one byte per channel per 8 bits, in blocks of [blockSizePerChannel] bytes
 * that alternate channel-by-channel (ch0 block, ch1 block, ch0 block, ...) rather than being
 * bit-interleaved sample-by-sample.
 *
 * [dsdBytesPerChannel] is already de-interleaved - index 0 is channel 0's whole raw DSD bitstream
 * as a contiguous byte array, ready for [DopEncoder.encode]. */
data class DsfAudio(
    val sampleRateHz: Int,
    val channelCount: Int,
    val dsdBytesPerChannel: List<ByteArray>,
)

/** Parses a .dsf file's bytes into raw per-channel DSD data. Returns null for anything that isn't
 * a well-formed 1-bit-per-sample DSF this app can actually decode - no partial/best-effort
 * output, since garbage DSD bits would be genuinely inaudible noise, not a degraded but usable
 * result. Not verified against real hardware (no DSD-capable DAC available to test with) - this
 * follows the published DSF spec, but only [DopEncoderTest]-style structural correctness is
 * unit-tested here, not "does a real DAC actually play it back as music". */
object DsfParser {
    private const val HEADER_SIZE = 28
    private const val FMT_CHUNK_HEADER_SIZE = 12
    private const val FMT_CHUNK_BODY_SIZE = 40
    private const val DATA_CHUNK_HEADER_SIZE = 12
    private const val SUPPORTED_BITS_PER_SAMPLE = 1

    fun parse(bytes: ByteArray): DsfAudio? {
        if (bytes.size < HEADER_SIZE + FMT_CHUNK_HEADER_SIZE + FMT_CHUNK_BODY_SIZE + DATA_CHUNK_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (!magicAt(bytes, 0, "DSD ")) return null
        if (!magicAt(bytes, HEADER_SIZE, "fmt ")) return null

        // fmt chunk body starts after its own 12-byte header (magic + int64 chunkSize).
        val fmtBody = HEADER_SIZE + FMT_CHUNK_HEADER_SIZE
        val channelNum = buf.getInt(fmtBody + 12)
        val samplingFrequency = buf.getInt(fmtBody + 16)
        val bitsPerSample = buf.getInt(fmtBody + 20)
        val sampleCount = buf.getLong(fmtBody + 24)
        val blockSizePerChannel = buf.getInt(fmtBody + 32)

        if (bitsPerSample != SUPPORTED_BITS_PER_SAMPLE) return null
        if (channelNum <= 0 || blockSizePerChannel <= 0 || samplingFrequency <= 0) return null

        val dataChunkOffset = HEADER_SIZE + FMT_CHUNK_HEADER_SIZE + FMT_CHUNK_BODY_SIZE
        if (!magicAt(bytes, dataChunkOffset, "data")) return null
        val dataStart = dataChunkOffset + DATA_CHUNK_HEADER_SIZE

        val bytesPerChannelTotal = ((sampleCount + 7) / 8).toInt()
        val channelData = List(channelNum) { ByteArray(bytesPerChannelTotal) }
        val writeIndex = IntArray(channelNum)

        var pos = dataStart
        var channel = 0
        while (pos + blockSizePerChannel <= bytes.size && writeIndex[channel % channelNum] < bytesPerChannelTotal) {
            val ch = channel % channelNum
            val remaining = bytesPerChannelTotal - writeIndex[ch]
            val toCopy = minOf(blockSizePerChannel, remaining)
            System.arraycopy(bytes, pos, channelData[ch], writeIndex[ch], toCopy)
            writeIndex[ch] += toCopy
            pos += blockSizePerChannel
            channel++
        }

        if (writeIndex.any { it != bytesPerChannelTotal }) return null

        return DsfAudio(sampleRateHz = samplingFrequency, channelCount = channelNum, dsdBytesPerChannel = channelData)
    }

    private fun magicAt(bytes: ByteArray, offset: Int, magic: String): Boolean {
        if (offset + magic.length > bytes.size) return false
        for (i in magic.indices) {
            if (bytes[offset + i] != magic[i].code.toByte()) return false
        }
        return true
    }
}
