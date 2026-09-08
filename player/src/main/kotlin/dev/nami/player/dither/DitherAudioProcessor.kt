package dev.nami.player.dither

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.toPcm16
import java.nio.ByteBuffer
import kotlin.random.Random

/** Этап 4's dithering - TPDF (triangular-PDF) dither at one LSB of the 16-bit stream, sitting
 * last in the chain after ReplayGain and the EQ. Off by default, same Beta/opt-in posture as the
 * rest of Аудиотракт.
 *
 * Honest about what this is: DefaultAudioSink's int pipeline hands every processor an ENCODING_
 * PCM_16BIT buffer, so by the time we see the samples the upstream stages have already rounded
 * their own output back to 16 bits. This therefore adds a ±1 LSB triangular noise floor that
 * decorrelates (masks) the quantization error those stages introduce, rather than being textbook
 * in-quantizer dither applied at the moment of requantization. Audibly it does the job it's there
 * for - turning correlated rounding artifacts on EQ'd/gain-adjusted audio into unshaped hiss --
 * but it is not a substitute for a higher-precision output path.
 *
 * ponytail: true in-quantizer dither would mean merging ReplayGain/EQ/dither into a single
 * processor that keeps float precision internally and only quantizes once at the end. Worth doing
 * if the noise floor ever measurably matters; not worth the coupling before then. */
class DitherAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    private var configured = false

    private val random = Random(System.nanoTime())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configured = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = enabled && configured

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.asShortBuffer()
        val outShorts = output.asShortBuffer()
        while (inShorts.hasRemaining()) {
            // Sum of two independent uniform(-0.5, 0.5) draws = triangular distribution, the
            // standard TPDF dither construction. One LSB of 16-bit PCM is 1.0 in short units.
            val noise = random.nextFloat() - 0.5f + random.nextFloat() - 0.5f
            outShorts.put((inShorts.get() + noise).toPcm16())
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onReset() {
        configured = false
    }
}
