package dev.nami.player.replaygain

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.log10
import kotlin.math.sqrt

/** Этап 4's ReplayGain - NOT true EBU R128 (no K-weighting, no gating, no true-peak limiting),
 * just RMS loudness over the whole decoded track vs a -18dBFS target. Runs once per track (result
 * cached in Track.replayGainDb), decodes with the platform's own MediaCodec so it costs nothing
 * extra beyond what playback already uses. */
object ReplayGainScanner {

    private const val TARGET_DBFS = -18.0

    /** Returns a gain in dB to apply so the track's RMS loudness lands near TARGET_DBFS, clamped
     * to +/-12dB (matches the EQ's own range - anything further off is more likely a scan
     * artifact than a real mix difference). Null on any decode failure - fails closed, silent,
     * same as BitPerfectUsbController. */
    fun scan(path: String): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sumSquares = 0.0
            var sampleCount = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val shortBuffer = outputBuffer.asShortBuffer()
                            // ponytail: PCM 16-bit only - MediaCodec's default decoder output
                            // format on Android; good enough for a loudness estimate regardless
                            // of the source file's own bit depth.
                            while (shortBuffer.hasRemaining()) {
                                val sample = shortBuffer.get() / 32768.0
                                sumSquares += sample * sample
                                sampleCount++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()

            if (sampleCount == 0L) return null
            val rms = sqrt(sumSquares / sampleCount)
            if (rms <= 0.0) return null
            val measuredDbfs = 20 * log10(rms)
            (TARGET_DBFS - measuredDbfs).toFloat().coerceIn(-12f, 12f)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
