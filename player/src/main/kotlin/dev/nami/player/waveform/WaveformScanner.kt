package dev.nami.player.waveform

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.abs
import kotlin.math.pow

/** Real per-track waveform for the Now Playing scrubber -- decodes the whole file once (same
 * MediaCodec/MediaExtractor approach as ReplayGainScanner) and reduces it to [BAR_COUNT] bucket
 * heights, so the scrubber shows this track's actual loudness contour instead of a plausible-
 * looking fake shape. */
object WaveformScanner {

    const val BAR_COUNT = 120

    /** One peak-amplitude value per bucket (0f..1f, gamma-compressed so quiet passages are still
     * visible instead of reading as flat silence next to a few loud peaks), [BAR_COUNT] of them
     * spanning the whole track. Null on any decode failure -- fails closed, caller falls back to
     * a placeholder shape rather than showing nothing. */
    fun scan(path: String): List<Float>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) return null

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val buckets = FloatArray(BAR_COUNT)
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
                            // Bucket index from the buffer's own presentation time, not a running
                            // sample count -- keeps this correct regardless of channel count or
                            // whether every bucket gets exactly the same number of samples.
                            val fraction = (bufferInfo.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0)
                            val bucket = (fraction * (BAR_COUNT - 1)).toInt().coerceIn(0, BAR_COUNT - 1)
                            val shortBuffer = outputBuffer.asShortBuffer()
                            var peak = 0f
                            while (shortBuffer.hasRemaining()) {
                                val amplitude = abs(shortBuffer.get() / 32768f)
                                if (amplitude > peak) peak = amplitude
                            }
                            if (peak > buckets[bucket]) buckets[bucket] = peak
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

            val maxPeak = buckets.max()
            if (maxPeak <= 0f) return null
            // Normalize to the track's own loudest moment (not absolute 0dBFS) so a quiet track
            // still fills the scrubber, then gamma-compress (sqrt) so quiet passages stay visible
            // instead of reading as a flat line next to a handful of loud peaks.
            buckets.map { (it / maxPeak).toDouble().pow(0.5).toFloat().coerceIn(0.05f, 1f) }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
