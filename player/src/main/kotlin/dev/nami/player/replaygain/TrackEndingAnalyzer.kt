package dev.nami.player.replaygain

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.sqrt

/** Этап 6's "умный кроссфейд" (План.md §22.9) - decides whether a track's own ending already
 * fades out naturally. A track that plays loud right up to a hard cut sounds chopped if
 * crossfade's own ~5s ramp starts on top of it; a track that already trails off on its own blends
 * fine. BPM/key-matching (the other half of "уместно" from the plan) isn't implemented anywhere
 * in this codebase yet - this covers only the ending-shape half, documented as a partial
 * implementation, not a silent gap. */
object TrackEndingAnalyzer {
    private const val TAIL_WINDOW_MS = 6_000L
    /** Tail RMS below this fraction of the track's own peak-bucket RMS counts as "already fading". */
    private const val NATURAL_FADE_THRESHOLD = 0.35

    /** Null on any decode failure (fails closed, same as ReplayGainScanner) - callers should
     * treat null the same as "assume abrupt" (crossfade still applies, matching today's
     * behavior) rather than blocking crossfade on an unreadable file. */
    fun endsWithNaturalFade(path: String): Boolean? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (durationUs <= 0 || sampleRate <= 0 || channelCount <= 0) return null

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Bucketed like WaveformScanner - one RMS value per ~500ms, so "the tail" and "the
            // loudest part elsewhere in the track" are both real aggregates, not single samples.
            val bucketMs = 500L
            val bucketCount = ((durationUs / 1000) / bucketMs).toInt().coerceAtLeast(1)
            val bucketSumSquares = DoubleArray(bucketCount)
            val bucketSampleCount = LongArray(bucketCount)

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
                            val bucket = ((bufferInfo.presentationTimeUs / 1000) / bucketMs).toInt().coerceIn(0, bucketCount - 1)
                            val shortBuffer = outputBuffer.asShortBuffer()
                            while (shortBuffer.hasRemaining()) {
                                val sample = shortBuffer.get() / 32768.0
                                bucketSumSquares[bucket] += sample * sample
                                bucketSampleCount[bucket]++
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

            val bucketRms = (0 until bucketCount).map { i ->
                if (bucketSampleCount[i] == 0L) 0.0 else sqrt(bucketSumSquares[i] / bucketSampleCount[i])
            }
            val peakRms = bucketRms.maxOrNull() ?: return null
            if (peakRms <= 0.0) return null

            val tailBucketCount = (TAIL_WINDOW_MS / bucketMs).toInt().coerceAtLeast(1).coerceAtMost(bucketCount)
            val tailRms = bucketRms.takeLast(tailBucketCount).average()
            (tailRms / peakRms) < NATURAL_FADE_THRESHOLD
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
