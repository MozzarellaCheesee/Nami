package dev.nami.player.replaygain

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.pow

/** Applies the current track's scanned ReplayGain (see ReplayGainScanner) as a flat linear
 * multiply on the float PCM stream. Same isActive()-only-checked-on-(re)build caveat as
 * ParametricEqAudioProcessor -- gain updates apply live, on/off needs a seek/track-change. */
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    @Volatile private var gainLinear: Float = 1f
    private var configured = false

    /** null (no scan result yet, or scan failed) means unity gain -- never silently distorts a
     * track we couldn't measure. */
    fun setGainDb(gainDb: Float?) {
        gainLinear = dbToLinear(gainDb)
    }

    companion object {
        fun dbToLinear(gainDb: Float?): Float = if (gainDb == null) 1f else 10f.pow(gainDb / 20f)
    }

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
        val gain = gainLinear
        while (inFloats.hasRemaining()) {
            outFloats.put((inFloats.get() * gain).coerceIn(-1f, 1f))
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onReset() {
        configured = false
    }
}
