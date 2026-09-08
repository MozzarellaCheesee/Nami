package dev.nami.player.eq

import kotlin.math.abs
import kotlin.math.ln

/** Imports AutoEQ's `ParametricEQ.txt` format (one line per filter: `Filter N: ON PK Fc <hz> Hz
 * Gain <db> dB Q <q>`, plus an optional `Preamp: <db> dB` line) into this app's fixed 9-band EQ
 * (ParametricEqAudioProcessor.BAND_FREQS_HZ).
 *
 * AutoEQ profiles are arbitrary-Fc/arbitrary-Q biquads (typically 5-10 of them) correcting a
 * specific headphone's measured response - this app's EQ only has 9 fixed-frequency peaking
 * bands, so an exact conversion isn't possible. This buckets each filter's gain onto whichever of
 * the 9 fixed bands is closest to it on a log-frequency scale (ties/overlaps sum), which is a real
 * approximation, not the original curve - close enough to be useful, not claimed to be exact. Q
 * is ignored entirely for the same reason (nothing to map it onto). */
object AutoEqParser {
    private val FILTER_LINE = Regex(
        """Filter\s+\d+:\s+ON\s+\w+\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB""",
        RegexOption.IGNORE_CASE,
    )
    private val PREAMP_LINE = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)

    /** Returns null if the text contains no recognizable filter lines at all (not a
     * ParametricEQ.txt file, or empty) - caller should show "не удалось распознать файл". */
    fun parse(text: String): List<Float>? {
        val bandFreqs = ParametricEqAudioProcessor.BAND_FREQS_HZ
        val bandGains = FloatArray(bandFreqs.size)
        var preampDb = 0f
        var matchedAny = false

        for (line in text.lineSequence()) {
            PREAMP_LINE.find(line)?.let { preampDb = it.groupValues[1].toFloat() }
            FILTER_LINE.find(line)?.let { match ->
                matchedAny = true
                val fc = match.groupValues[1].toFloat()
                val gain = match.groupValues[2].toFloat()
                val nearestBand = bandFreqs.indices.minBy { abs(ln(bandFreqs[it] / fc)) }
                bandGains[nearestBand] += gain
            }
        }
        if (!matchedAny) return null
        return bandGains.map { (it + preampDb).coerceIn(-12f, 12f) }
    }
}
