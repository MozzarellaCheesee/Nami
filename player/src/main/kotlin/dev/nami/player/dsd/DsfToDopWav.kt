package dev.nami.player.dsd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bridges [DsfParser] + [DopEncoder] to something Media3 can actually play: a standard 24-bit
 * PCM .wav file carrying DoP-packed DSD. This is the "wire it to a real playback path" this
 * codebase was missing -- rather than writing a custom Extractor/MediaSource for raw DSD (a much
 * bigger, riskier piece for a format that can't be verified without real DSD-capable hardware),
 * this converts once at import time and lets the existing WAV extractor + normal DSP pipeline
 * handle it exactly like any other imported track. A DAC that recognizes DoP's marker bytes will
 * unpack this back to native DSD; one that doesn't just hears it as (very high sample rate,
 * inaudible-as-such) PCM, per the DoP spec's whole design.
 *
 * Cost of this approach: the converted file is exactly as large as the DoP stream it carries (no
 * compression), and it's a one-time transcode rather than true real-time streaming decode -- a
 * fully streamed path would need the custom Extractor mentioned above. */
object DsfToDopWav {

    /** Returns the complete bytes of a playable .wav file, or null if [dsfBytes] isn't a DSF this
     * app can parse (see [DsfParser.parse]) or has an odd per-channel DSD byte count ([DopEncoder]
     * requires pairs of DSD bytes per PCM frame). */
    fun convert(dsfBytes: ByteArray): ByteArray? {
        val audio = DsfParser.parse(dsfBytes) ?: return null
        if (audio.dsdBytesPerChannel.any { it.size % 2 != 0 }) return null

        val dopPerChannel = audio.dsdBytesPerChannel.map { DopEncoder.encode(it) }
        val frameCount = dopPerChannel.minOf { it.size / 3 }
        val channelCount = audio.channelCount

        val pcmData = ByteArray(frameCount * channelCount * 3)
        var outIndex = 0
        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                val src = dopPerChannel[ch]
                val srcIndex = frame * 3
                pcmData[outIndex] = src[srcIndex]
                pcmData[outIndex + 1] = src[srcIndex + 1]
                pcmData[outIndex + 2] = src[srcIndex + 2]
                outIndex += 3
            }
        }

        // DoP packs 2 DSD bytes (16 bits) per 24-bit PCM frame -- see DopEncoder's own doc.
        val dopSampleRateHz = audio.sampleRateHz / 16
        return wavHeader(dopSampleRateHz, channelCount, bitsPerSample = 24, dataSize = pcmData.size) + pcmData
    }

    private fun wavHeader(sampleRateHz: Int, channelCount: Int, bitsPerSample: Int, dataSize: Int): ByteArray {
        val blockAlign = channelCount * (bitsPerSample / 8)
        val byteRate = sampleRateHz * blockAlign
        val out = ByteArrayOutputStream(44)
        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLe(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun writeShortLe(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())

        writeString("RIFF")
        writeIntLe(36 + dataSize)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLe(16)
        writeShortLe(1) // PCM
        writeShortLe(channelCount)
        writeIntLe(sampleRateHz)
        writeIntLe(byteRate)
        writeShortLe(blockAlign)
        writeShortLe(bitsPerSample)
        writeString("data")
        writeIntLe(dataSize)
        return out.toByteArray()
    }
}
