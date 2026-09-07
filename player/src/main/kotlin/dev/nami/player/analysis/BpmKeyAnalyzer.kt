package dev.nami.player.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BpmKeyResult(val bpm: Float?, val musicalKey: String?)

/** Этап 6/§22.13's "правила автоочереди" needs a BPM/key signal that doesn't exist anywhere in
 * this codebase yet -- this is that signal, real DSP, not a stub:
 *
 * **BPM**: a short-time RMS envelope (11.6ms hops) of the decoded mono signal, half-wave
 * rectified onset strength (positive energy jumps = likely beats), then autocorrelation of that
 * onset curve over the lag range for 50-200 BPM -- the lag with the strongest self-similarity is
 * the beat period. This is a simplified, single-pass version of standard onset-based tempo
 * estimation (no multi-band onset detection, no dynamic-programming beat tracking) -- honest
 * about being an estimate, not a claim of DJ-software-grade accuracy.
 *
 * **Key**: a chromagram (12 pitch-class bins) built from windowed FFT magnitude spectra, then
 * correlated against the Krumhansl-Schmuckler major/minor key profiles for all 12 roots (24
 * candidates) -- the classic, well-documented approach, not a novel algorithm.
 *
 * Both only look at the first [ANALYSIS_DURATION_MS] of the track (bounds memory/CPU for a full
 * decode+FFT pass) -- a real limitation for tracks that change tempo/key partway through, not
 * pretended away. Null fields on any decode failure or an unreadable/too-short result -- fails
 * closed, same as ReplayGainScanner/TrackEndingAnalyzer in this same package. */
object BpmKeyAnalyzer {
    private const val ANALYSIS_DURATION_MS = 90_000L
    private const val MIN_BPM = 50.0
    private const val MAX_BPM = 200.0
    private const val ENVELOPE_HOP_SAMPLES = 512
    private const val FFT_SIZE = 4096
    private const val FFT_HOP = FFT_SIZE / 2
    private const val MIN_CHROMA_HZ = 80.0
    private const val MAX_CHROMA_HZ = 5000.0

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    // Krumhansl-Schmuckler key profiles, C-rooted (index 0 = C).
    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun scan(path: String): BpmKeyResult {
        val decoded = decodeMono(path) ?: return BpmKeyResult(null, null)
        val bpm = estimateBpm(decoded.samples, decoded.sampleRateHz)
        val key = estimateKey(decoded.samples, decoded.sampleRateHz)
        return BpmKeyResult(bpm, key)
    }

    private data class DecodedMono(val samples: FloatArray, val sampleRateHz: Int)

    /** Decodes up to [ANALYSIS_DURATION_MS] of the track, downmixed to mono float samples. Reuses
     * the same MediaExtractor/MediaCodec decode loop shape as ReplayGainScanner/
     * TrackEndingAnalyzer in this package -- platform decoder, no extra native dependency. */
    private fun decodeMono(path: String): DecodedMono? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate <= 0 || channelCount <= 0) return null

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamples = (ANALYSIS_DURATION_MS / 1000.0 * sampleRate).toInt()
            val output = FloatArray(maxSamples)
            var written = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && written < maxSamples) {
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
                            val frameCount = shortBuffer.remaining() / channelCount
                            var f = 0
                            while (f < frameCount && written < maxSamples) {
                                var sum = 0f
                                for (c in 0 until channelCount) sum += shortBuffer.get() / 32768f
                                output[written] = sum / channelCount
                                written++
                                f++
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

            if (written < sampleRate) return null // less than 1s decoded -- too short to analyze
            DecodedMono(output.copyOf(written), sampleRate)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun estimateBpm(samples: FloatArray, sampleRateHz: Int): Float? {
        if (samples.size < ENVELOPE_HOP_SAMPLES * 4) return null
        val envelopeSize = samples.size / ENVELOPE_HOP_SAMPLES
        val envelope = DoubleArray(envelopeSize)
        for (i in 0 until envelopeSize) {
            var sumSquares = 0.0
            val start = i * ENVELOPE_HOP_SAMPLES
            for (s in start until minOf(start + ENVELOPE_HOP_SAMPLES, samples.size)) {
                sumSquares += samples[s].toDouble() * samples[s]
            }
            envelope[i] = sqrt(sumSquares / ENVELOPE_HOP_SAMPLES)
        }
        // Half-wave rectified onset strength -- only positive energy jumps count as likely beats.
        val onset = DoubleArray(envelopeSize)
        for (i in 1 until envelopeSize) onset[i] = max(0.0, envelope[i] - envelope[i - 1])

        val hopSeconds = ENVELOPE_HOP_SAMPLES.toDouble() / sampleRateHz
        val minLag = (60.0 / MAX_BPM / hopSeconds).roundToInt().coerceAtLeast(1)
        val maxLag = (60.0 / MIN_BPM / hopSeconds).roundToInt()
        if (maxLag >= envelopeSize || minLag >= maxLag) return null

        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (i in 0 until envelopeSize - lag) score += onset[i] * onset[i + lag]
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore <= 0.0) return null
        val bpm = 60.0 / (bestLag * hopSeconds)
        return bpm.toFloat().takeIf { it in MIN_BPM.toFloat()..MAX_BPM.toFloat() }
    }

    private fun estimateKey(samples: FloatArray, sampleRateHz: Int): String? {
        if (samples.size < FFT_SIZE) return null
        val chroma = DoubleArray(12)
        val window = DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (FFT_SIZE - 1)) } // Hann

        var frameStart = 0
        var framesUsed = 0
        while (frameStart + FFT_SIZE <= samples.size) {
            val real = DoubleArray(FFT_SIZE) { i -> samples[frameStart + i] * window[i] }
            val imag = DoubleArray(FFT_SIZE)
            Fft.transform(real, imag)

            for (k in 1 until FFT_SIZE / 2) {
                val freq = k.toDouble() * sampleRateHz / FFT_SIZE
                if (freq < MIN_CHROMA_HZ || freq > MAX_CHROMA_HZ) continue
                val magnitude = sqrt(real[k] * real[k] + imag[k] * imag[k])
                val midi = 69 + 12 * log2(freq / 440.0)
                val pitchClass = (((midi.roundToInt() - 60) % 12) + 12) % 12
                chroma[pitchClass] += magnitude
            }
            framesUsed++
            frameStart += FFT_HOP
        }
        if (framesUsed == 0 || chroma.sum() <= 0.0) return null

        var bestCorrelation = Double.NEGATIVE_INFINITY
        var bestRoot = 0
        var bestIsMajor = true
        for (root in 0 until 12) {
            val majorCorrelation = correlate(chroma, MAJOR_PROFILE, root)
            val minorCorrelation = correlate(chroma, MINOR_PROFILE, root)
            if (majorCorrelation > bestCorrelation) {
                bestCorrelation = majorCorrelation
                bestRoot = root
                bestIsMajor = true
            }
            if (minorCorrelation > bestCorrelation) {
                bestCorrelation = minorCorrelation
                bestRoot = root
                bestIsMajor = false
            }
        }
        return "${NOTE_NAMES[bestRoot]} ${if (bestIsMajor) "Major" else "Minor"}"
    }

    /** Pearson correlation between the measured chroma and a key profile rotated so its tonic
     * sits at [root]. */
    private fun correlate(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        val rotated = DoubleArray(12) { i -> profile[(i - root + 12) % 12] }
        val chromaMean = chroma.average()
        val profileMean = rotated.average()
        var numerator = 0.0
        var chromaVariance = 0.0
        var profileVariance = 0.0
        for (i in 0 until 12) {
            val chromaDelta = chroma[i] - chromaMean
            val profileDelta = rotated[i] - profileMean
            numerator += chromaDelta * profileDelta
            chromaVariance += chromaDelta * chromaDelta
            profileVariance += profileDelta * profileDelta
        }
        val denominator = sqrt(chromaVariance * profileVariance)
        return if (denominator <= 0.0) 0.0 else numerator / denominator
    }
}
