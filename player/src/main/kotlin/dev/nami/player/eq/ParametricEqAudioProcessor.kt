package dev.nami.player.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Этап 4's parametric EQ -- three fixed bands (bass/mid/treble, peaking filters) instead of a
 * full arbitrary-band editor: real DSP on the actual float PCM stream, wired into DefaultAudioSink
 * via NamiRenderersFactory, gated off by default (Settings -> Аудиотракт, Beta) since this is the
 * one processor sitting directly in the path of every second of audio the app ever plays -- a
 * subtle bug here means "everything sounds wrong", not "one feature is broken", so it stays
 * strictly opt-in until it's had real listening verification beyond this session.
 *
 * ponytail: only accepts ENCODING_PCM_FLOAT input (ExoPlayer's DefaultAudioSink already prefers
 * float when setEnableFloatOutput(true) is set, which NamiRenderersFactory does) -- isActive()
 * returns false for any other encoding, so it never touches a format it wasn't verified against.
 */
class ParametricEqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var bassCoeffs = BiquadCoefficients.IDENTITY
    @Volatile private var midCoeffs = BiquadCoefficients.IDENTITY
    @Volatile private var trebleCoeffs = BiquadCoefficients.IDENTITY
    // isActive() is only consulted when DefaultAudioSink (re)builds its processing pipeline --
    // on track change, or an explicit seek. Toggling this mid-track doesn't retroactively
    // splice the processor in or out of an already-built pipeline; the gain values below DO take
    // effect immediately (queueInput reads the current coefficients every buffer), only the
    // on/off switch itself needs a seek/track-change to actually engage or disengage.
    @Volatile var enabled: Boolean = false

    private var sampleRateHz = 0
    private var bassState = emptyArray<BiquadState>()
    private var midState = emptyArray<BiquadState>()
    private var trebleState = emptyArray<BiquadState>()

    /** Called from Settings' live flow -- gains in dB, ±12 typical range. Recomputes coefficients
     * immediately; @Volatile field swap means the audio thread picks up the new filter on its
     * very next buffer, no restart needed. */
    fun setGains(bassDb: Float, midDb: Float, trebleDb: Float) {
        if (sampleRateHz <= 0) return
        bassCoeffs = BiquadCoefficients.peaking(sampleRateHz, 100f, bassDb, 0.7f)
        midCoeffs = BiquadCoefficients.peaking(sampleRateHz, 1000f, midDb, 0.7f)
        trebleCoeffs = BiquadCoefficients.peaking(sampleRateHz, 8000f, trebleDb, 0.7f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        bassState = Array(inputAudioFormat.channelCount) { BiquadState() }
        midState = Array(inputAudioFormat.channelCount) { BiquadState() }
        trebleState = Array(inputAudioFormat.channelCount) { BiquadState() }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = enabled && sampleRateHz > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val channelCount = bassState.size
        val output = replaceOutputBuffer(remaining)
        val inFloats = inputBuffer.asFloatBuffer()
        val outFloats = output.asFloatBuffer()
        val bass = bassCoeffs
        val mid = midCoeffs
        val treble = trebleCoeffs
        var channel = 0
        while (inFloats.hasRemaining()) {
            var sample = inFloats.get()
            sample = bassState[channel].process(sample, bass)
            sample = midState[channel].process(sample, mid)
            sample = trebleState[channel].process(sample, treble)
            outFloats.put(sample.coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onFlush() {
        bassState.forEach { it.reset() }
        midState.forEach { it.reset() }
        trebleState.forEach { it.reset() }
    }

    override fun onReset() {
        sampleRateHz = 0
        bassState = emptyArray()
        midState = emptyArray()
        trebleState = emptyArray()
    }
}
