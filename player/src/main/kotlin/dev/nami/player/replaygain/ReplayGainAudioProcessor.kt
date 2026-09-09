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
    private var channelCount = 2
    private val ramp = GainRamp()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputIsFloat = inputAudioFormat.requireNamiDspInput()
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        ramp.configure(inputAudioFormat.sampleRate, totalGainLinear())
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
        val target = totalGainLinear()
        val output = replaceOutputBuffer(sampleCount * 4)
        val outFloats = output.asFloatBuffer()
        var i = 0
        while (i < sampleCount) {
            // Один множитель на кадр, а не на отсчёт: иначе каналы разъезжались бы по громкости
            // внутри одного кадра, а это уже смещение стереообраза, а не изменение уровня.
            val gain = ramp.nextFrameGain(target)
            var ch = 0
            while (ch < channelCount && i < sampleCount) {
                outFloats.put(scratch[i] * gain)
                i++
                ch++
            }
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(sampleCount * 4).flip()
    }

    /** Сик - и так разрыв сигнала, сглаживать через него нечего: множитель ставится сразу. */
    override fun onFlush() {
        ramp.snapTo(totalGainLinear())
    }

    override fun onReset() {
        configured = false
    }
}

/** Плавный переход множителя громкости вместо мгновенного скачка.
 *
 * Зачем: при гэплесс-переходе (см. NamiRenderersFactory) конвейер между треками НЕ перестраивается,
 * поэтому смена ReplayGain соседних треков раньше меняла множитель ровно на границе - ступенька
 * амплитуды в один отсчёт, то есть слышимый щелчок именно там, где пауза как раз и не должна была
 * появиться. То же касается живого изменения "усиления воспроизведения" ползунком.
 *
 * Однополюсное сглаживание с постоянной времени [TIME_CONSTANT_S]: достаточно быстро, чтобы
 * выравнивание громкости успевало к началу трека, и достаточно медленно, чтобы не звучать как
 * фронт. */
internal class GainRamp {
    private var alpha = 1f
    private var current = 1f

    fun configure(sampleRate: Int, initialGain: Float) {
        // Первое значение берём как есть: плавно въезжать в громкость с 1.0 на старте трека -
        // это как раз тот фейд, которого тут быть не должно.
        current = initialGain
        alpha = if (sampleRate > 0) {
            (1f - kotlin.math.exp(-1f / (sampleRate * TIME_CONSTANT_S))).coerceIn(1e-6f, 1f)
        } else {
            1f
        }
    }

    fun snapTo(gain: Float) {
        current = gain
    }

    fun nextFrameGain(target: Float): Float {
        current += (target - current) * alpha
        return current
    }

    companion object {
        private const val TIME_CONSTANT_S = 0.02f
    }
}
