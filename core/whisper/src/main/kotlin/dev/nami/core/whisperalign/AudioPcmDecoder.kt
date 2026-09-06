package dev.nami.core.whisperalign

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes a local audio file to 16kHz mono f32 PCM -- the exact format whisper.cpp requires.
 * Whole-track decode into memory (a 4-minute track is ~15MB as f32 mono @16kHz), which is fine
 * since this only runs once per track, on explicit user request. */
object AudioPcmDecoder {

    fun decodeTo16kMono(path: String): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val pcmChunks = ArrayList<ShortArray>()
            var totalSamples = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = ShortArray(bufferInfo.size / 2)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        pcmChunks.add(shorts)
                        totalSamples += shorts.size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
            codec.stop()
            codec.release()
            extractor.release()

            val interleaved = ShortArray(totalSamples)
            var pos = 0
            for (chunk in pcmChunks) {
                chunk.copyInto(interleaved, pos)
                pos += chunk.size
            }

            val mono = downmixToMono(interleaved, srcChannels)
            return resampleLinear(mono, srcSampleRate, TARGET_SAMPLE_RATE)
        } catch (e: Exception) {
            extractor.release()
            return null
        }
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) {
            return FloatArray(interleaved.size) { interleaved[it] / 32768f }
        }
        val frames = interleaved.size / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) sum += interleaved[frame * channels + ch]
            mono[frame] = (sum / channels) / 32768f
        }
        return mono
    }

    // ponytail: linear interpolation, not a proper sinc/polyphase resampler -- whisper.cpp only
    // needs "close enough" audio for transcription, not audiophile fidelity.
    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLength = (input.size / ratio).toInt()
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = input.getOrElse(idx) { 0f }
            val b = input.getOrElse(idx + 1) { a }
            output[i] = a + (b - a) * frac
        }
        return output
    }

    const val TARGET_SAMPLE_RATE = 16_000
}
