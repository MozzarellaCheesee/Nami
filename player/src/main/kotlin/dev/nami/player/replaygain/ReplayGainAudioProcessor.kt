package dev.nami.player.replaygain

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.toPcm16
import java.nio.ByteBuffer
import kotlin.math.pow

/** Applies the current track's scanned ReplayGain (see ReplayGainScanner) as a flat linear
 * multiply on the int16 PCM stream. Same isActive()-only-checked-on-(re)build caveat as
 * ParametricEqAudioProcessor -- gain updates apply live, on/off needs a seek/track-change. */
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    @Volatile private var trackGainDb: Float? = null
    // "Усиление воспроизведения" (Выкл/+3dB/+6dB) -- a flat library-wide boost, independent of
    // ReplayGain's per-track measured gain. Applies even when ReplayGain itself is off, since it's
    // a manual "make everything louder" knob, not a loudness-matching one.
    @Volatile var boostDb: Float = 0f
    private var configured = false

    /** null (no scan result yet, or scan failed) means the ReplayGain term contributes 0dB --
     * never silently distorts a track we couldn't measure. */
    fun setGainDb(gainDb: Float?) {
        trackGainDb = gainDb
    }

    private fun totalGainLinear(): Float {
        val trackDb = if (enabled) (trackGainDb ?: 0f) else 0f
        return dbToLinear(trackDb + boostDb)
    }

    companion object {
        fun dbToLinear(gainDb: Float?): Float = if (gainDb == null) 1f else 10f.pow(gainDb / 20f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configured = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = configured && (enabled || boostDb != 0f)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.asShortBuffer()
        val outShorts = output.asShortBuffer()
        val gain = totalGainLinear()
        while (inShorts.hasRemaining()) {
            outShorts.put((inShorts.get() * gain).toPcm16())
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onReset() {
        configured = false
    }
}
