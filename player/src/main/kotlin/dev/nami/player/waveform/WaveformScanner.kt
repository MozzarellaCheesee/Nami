package dev.nami.player.waveform

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.sqrt

/** Real per-track waveform for the Now Playing scrubber - decodes the whole file once (same
 * MediaCodec/MediaExtractor approach as ReplayGainScanner) and reduces it to [BAR_COUNT] bucket
 * heights, so the scrubber shows this track's actual loudness contour instead of a plausible-
 * looking fake shape. */
object WaveformScanner {

    const val BAR_COUNT = 120

    /** One RMS-loudness value per bucket (0f..1f, normalized to the track's own loudest bucket),
     * [BAR_COUNT] of them spanning the whole track. RMS, not peak: most modern masters sit at or
     * near full-scale peak almost everywhere (the loudness-war look), which made a peak-based
     * scan draw a near-flat "brick" - RMS tracks perceived loudness instead, which actually
     * varies through a track's quiet/loud sections and looks like a real waveform. Null on any
     * decode failure - fails closed, caller falls back to a placeholder shape. */
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

            val bucketSumSquares = DoubleArray(BAR_COUNT)
            val bucketSampleCounts = LongArray(BAR_COUNT)
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
                            // sample count - keeps this correct regardless of channel count or
                            // whether every bucket gets exactly the same number of samples.
                            val fraction = (bufferInfo.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0)
                            val bucket = (fraction * (BAR_COUNT - 1)).toInt().coerceIn(0, BAR_COUNT - 1)
                            val shortBuffer = outputBuffer.asShortBuffer()
                            while (shortBuffer.hasRemaining()) {
                                val sample = shortBuffer.get() / 32768.0
                                bucketSumSquares[bucket] += sample * sample
                                bucketSampleCounts[bucket]++
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

            val rms = DoubleArray(BAR_COUNT) { i ->
                if (bucketSampleCounts[i] > 0) sqrt(bucketSumSquares[i] / bucketSampleCounts[i]) else 0.0
            }
            val maxRms = rms.max()
            if (maxRms <= 0.0) return null
            // Normalize to the track's own loudest bucket (not absolute 0dBFS) so a quiet track
            // still fills the scrubber. No gamma curve on top - RMS values already span a real
            // range track-to-track (unlike peak), a compression curve here just flattens that
            // range back out the same way the old sqrt() did.
            val normalized = rms.map { (it / maxRms).toFloat().coerceIn(0.05f, 1f) }
            smooth(normalized)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** 5-tap weighted moving average - takes the edge off bucket-to-bucket jaggedness (adjacent
     * buckets can land on either side of a hard transient/silence boundary and swing wildly)
     * without eroding the track's actual loud/quiet contour, which a heavier smoothing window or
     * another gamma curve would. Edge buckets use a shorter, still-centered window instead of
     * padding with zeros, so the very start/end of the track don't fade toward silence for a
     * reason that has nothing to do with the audio itself. */
    private fun smooth(values: List<Float>): List<Float> {
        val weights = floatArrayOf(1f, 2f, 4f, 2f, 1f)
        val radius = weights.size / 2
        return values.indices.map { i ->
            var weightedSum = 0f
            var weightTotal = 0f
            for (offset in -radius..radius) {
                val j = i + offset
                if (j in values.indices) {
                    val weight = weights[offset + radius]
                    weightedSum += values[j] * weight
                    weightTotal += weight
                }
            }
            weightedSum / weightTotal
        }
    }
}
