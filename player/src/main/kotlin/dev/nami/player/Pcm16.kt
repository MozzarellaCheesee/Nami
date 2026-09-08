package dev.nami.player

import kotlin.math.roundToInt

/** Shared int16 conversion for the three DSP AudioProcessors (ReplayGain/EQ/dither).
 *
 * They all work in "short units" - a float in the ±32768 range, i.e. the sample value itself
 * rather than a normalized ±1.0. Biquads and a flat gain are both linear, so the scale makes no
 * difference to the math, and staying in short units means one multiply/divide less per sample and
 * no chance of a normalization factor drifting out of sync between stages.
 *
 * Rounds (not truncates) and clamps - truncation would add a half-LSB DC-ish bias on every stage,
 * clamping is what keeps a +6dB boost from wrapping loud samples into the opposite polarity
 * (which is what "distortion" actually sounds like when it's an overflow rather than a clip). */
internal fun Float.toPcm16(): Short = roundToInt().coerceIn(-32768, 32767).toShort()
