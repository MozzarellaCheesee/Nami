package dev.nami.player.replaygain

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.asFloatOutput
import dev.nami.player.normalizedSampleCount
import dev.nami.player.readNormalized
import dev.nami.player.requireNamiDspInput
import java.nio.ByteBuffer
import kotlin.math.pow

/** Applies the current track's scanned ReplayGain (see ReplayGainScanner) as a flat linear
 * multiply on the int16 PCM stream. Same isActive()-only-checked-on-(re)build caveat as
 * ParametricEqAudioProcessor - gain updates apply live, on/off needs a seek/track-change. */
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    @Volatile private var trackGainDb: Float? = null
    // "Усиление воспроизведения" (Выкл/+3dB/+6dB) - a flat library-wide boost, independent of
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

    private var inputIsFloat = false
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputIsFloat = inputAudioFormat.requireNamiDspInput()
        configured = true
        return inputAudioFormat.asFloatOutput()
    }

    override fun isActive(): Boolean = configured && (enabled || boostDb != 0f)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sampleCount = inputBuffer.normalizedSampleCount(inputIsFloat)
        if (sampleCount == 0) return
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        inputBuffer.readNormalized(scratch, sampleCount, inputIsFloat)

        // Усиление здесь НЕ ограничивается: клип делает только финальный квантователь, один раз.
        // Ограничить тут значило бы срезать пик, который следующая стадия (например, EQ с
        // отрицательным гейном) всё равно вернула бы в диапазон.
        val gain = totalGainLinear()
        val output = replaceOutputBuffer(sampleCount * 4)
        val outFloats = output.asFloatBuffer()
        for (i in 0 until sampleCount) outFloats.put(scratch[i] * gain)

        inputBuffer.position(inputBuffer.limit())
        output.position(sampleCount * 4).flip()
    }

    override fun onReset() {
        configured = false
    }
}
