package dev.nami.player.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.toPcm16
import java.nio.ByteBuffer

/** Этап 4's parametric EQ - a 9-band graphic EQ (ISO-ish octave centers: 63/125/250/500/1k/2k/
 * 4k/8k/16k Hz, peaking filters at Q=1.0), real DSP on the actual PCM stream, wired into
 * DefaultAudioSink via NamiRenderersFactory, gated off by default (Settings -> Аудиотракт, Beta)
 * since this is the one processor sitting directly in the path of every second of audio the app
 * ever plays - a subtle bug here means "everything sounds wrong", not "one feature is broken".
 *
 * Operates on ENCODING_PCM_16BIT, which is what DefaultAudioSink's int pipeline (ToInt16Pcm ->
 * channel mapping -> trimming -> here) actually hands a custom processor. See
 * NamiRenderersFactory's doc for why the float variant of this was both inert and actively broken.
 * The biquad itself runs on the raw sample value as a Float (±32768 range) - a linear filter
 * doesn't care about the scale, so there's no normalization step to get wrong. */
class ParametricEqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var coeffs: Array<BiquadCoefficients> = Array(BAND_FREQS_HZ.size) { BiquadCoefficients.IDENTITY }
    // isActive() is only consulted when DefaultAudioSink (re)builds its processing pipeline --
    // on track change, or an explicit seek. Toggling this mid-track doesn't retroactively splice
    // the processor in or out of an already-built pipeline; the gain values below DO take effect
    // immediately (queueInput reads the current coefficients every buffer), only the on/off
    // switch itself needs a seek/track-change to actually engage or disengage.
    @Volatile var enabled: Boolean = false

    private var sampleRateHz = 0
    private var states: Array<Array<BiquadState>> = emptyArray() // [band][channel]

    // Kept as dB, not only as computed coefficients: the biquad math needs a sample rate, which is
    // only known once onConfigure() runs. Settings' StateFlow emits its stored gains the moment the
    // service collects it - i.e. before any audio format is known - and the old code just dropped
    // that emission on the floor, so saved EQ gains stayed inert until the user physically moved a
    // slider again. Remembering them here and recomputing on configure fixes that, and is also what
    // lets a freshly built processor (one per player, see PlaybackService) start out correct.
    @Volatile private var gainsDb: List<Float> = List(BAND_FREQS_HZ.size) { 0f }

    /** Called from Settings' live flow - one gain per band in BAND_FREQS_HZ order, dB, ±12
     * typical range. Recomputes coefficients immediately; @Volatile field swap means the audio
     * thread picks up the new filter on its very next buffer, no restart needed. */
    fun setGains(gainsDb: List<Float>) {
        require(gainsDb.size == BAND_FREQS_HZ.size) { "expected ${BAND_FREQS_HZ.size} gains, got ${gainsDb.size}" }
        this.gainsDb = gainsDb
        recomputeCoefficients()
    }

    private fun recomputeCoefficients() {
        if (sampleRateHz <= 0) return
        val gains = gainsDb
        coeffs = Array(BAND_FREQS_HZ.size) { i -> BiquadCoefficients.peaking(sampleRateHz, BAND_FREQS_HZ[i], gains[i], BAND_Q) }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        states = Array(BAND_FREQS_HZ.size) { Array(inputAudioFormat.channelCount) { BiquadState() } }
        recomputeCoefficients()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = enabled && sampleRateHz > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val channelCount = states.getOrNull(0)?.size ?: return
        val output = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.asShortBuffer()
        val outShorts = output.asShortBuffer()
        val currentCoeffs = coeffs
        var channel = 0
        while (inShorts.hasRemaining()) {
            var sample = inShorts.get().toFloat()
            for (band in BAND_FREQS_HZ.indices) {
                sample = states[band][channel].process(sample, currentCoeffs[band])
            }
            outShorts.put(sample.toPcm16())
            channel = (channel + 1) % channelCount
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onFlush() {
        states.forEach { band -> band.forEach { it.reset() } }
    }

    override fun onReset() {
        sampleRateHz = 0
        states = emptyArray()
    }

    companion object {
        val BAND_FREQS_HZ = listOf(63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_Q = 1.0f
    }
}
