package dev.nami.player.eq

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Direct Form I biquad coefficients (RBJ Audio EQ Cookbook), already normalized by a0. */
data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
) {
    companion object {
        val IDENTITY = BiquadCoefficients(1f, 0f, 0f, 0f, 0f)

        /** Peaking (bell) filter - what a "band" in a graphic/parametric EQ actually is. */
        fun peaking(sampleRateHz: Int, freqHz: Float, gainDb: Float, q: Float): BiquadCoefficients {
            if (gainDb == 0f) return IDENTITY
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freqHz / sampleRateHz
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = kotlin.math.cos(w0)

            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cosW0
            val b2 = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha / a

            return BiquadCoefficients(
                (b0 / a0).toFloat(),
                (b1 / a0).toFloat(),
                (b2 / a0).toFloat(),
                (a1 / a0).toFloat(),
                (a2 / a0).toFloat(),
            )
        }
    }
}

/** Per-channel filter state (Direct Form I needs the last 2 in/out samples) - one instance per
 * audio channel, since a stereo signal's left/right histories must never mix. */
class BiquadState {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(input: Float, c: BiquadCoefficients): Float {
        val y = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}
