package dev.nami.player.dither

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.random.Random

/** Этап 4's dithering -- TPDF (triangular-PDF) dither, one LSB of a 16-bit target, added before
 * the float stream gets truncated to the output device's actual bit depth further down the sink
 * pipeline. Most phone/Bluetooth outputs are 16-bit regardless of the source file, so this softens
 * quantization noise on EQ'd/gain-adjusted audio into unshaped noise floor instead of a
 * correlated (audible) artifact -- the classic reason to dither at all. Off by default, same
 * Beta/opt-in posture as the rest of Аудиотракт. */
class DitherAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    private var configured = false

    // One LSB of 16-bit signed PCM in the [-1, 1] float range.
    private val lsb = 1f / 32768f
    private val random = Random(System.nanoTime())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
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
        val inFloats = inputBuffer.asFloatBuffer()
        val outFloats = output.asFloatBuffer()
        while (inFloats.hasRemaining()) {
            // Sum of two independent uniform(-0.5, 0.5) draws = triangular distribution, the
            // standard TPDF dither construction.
            val noise = (random.nextFloat() - 0.5f + random.nextFloat() - 0.5f) * lsb
            outFloats.put((inFloats.get() + noise).coerceIn(-1f, 1f))
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onReset() {
        configured = false
    }
}
