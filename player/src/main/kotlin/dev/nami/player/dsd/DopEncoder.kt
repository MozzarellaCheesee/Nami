package dev.nami.player.dsd

/** DoP (DSD-over-PCM, per the DoP Open Standard v1.1) - packs raw 1-bit DSD bytes into 24-bit
 * PCM frames with the standard alternating 0x05/0xFA marker bytes, so a DSD stream can ride over
 * an ordinary PCM AudioTrack path (any DAC that recognizes DoP unpacks it back to native DSD).
 *
 * Standalone and unit-tested (see DopEncoderTest), but NOT wired to a real playback path in this
 * pass - that needs a .dsf/.dff container parser to actually produce the 1-bit DSD payload
 * bytes this class expects, which doesn't exist in this codebase yet. Wiring it in without that
 * source of real DSD bytes would just be encoding silence.
 */
object DopEncoder {
    private const val MARKER_EVEN: Int = 0x05
    private const val MARKER_ODD: Int = 0xFA

    /**
     * [dsdBytes] is raw 1-bit DSD data, one byte = 8 consecutive DSD bits, single channel.
     * Returns 24-bit PCM frames (3 bytes each, big-endian: marker, dsdByte, dsdByte) at 1/16th
     * the DSD bit rate per the DoP spec (2 DSD bytes = 16 bits packed per marker frame) - e.g.
     * DSD64 (2.8224 MHz) becomes a 176.4kHz/24-bit PCM stream.
     */
    fun encode(dsdBytes: ByteArray): ByteArray {
        require(dsdBytes.size % 2 == 0) { "DoP packs 2 DSD bytes per 24-bit frame - odd-length input can't be split evenly" }
        val frameCount = dsdBytes.size / 2
        val output = ByteArray(frameCount * 3)
        for (frame in 0 until frameCount) {
            val marker = if (frame % 2 == 0) MARKER_EVEN else MARKER_ODD
            val outIndex = frame * 3
            output[outIndex] = marker.toByte()
            output[outIndex + 1] = dsdBytes[frame * 2]
            output[outIndex + 2] = dsdBytes[frame * 2 + 1]
        }
        return output
    }
}
