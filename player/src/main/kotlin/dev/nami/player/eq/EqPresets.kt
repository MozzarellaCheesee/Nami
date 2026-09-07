package dev.nami.player.eq

/** Fixed 9-band presets, gains in ParametricEqAudioProcessor.BAND_FREQS_HZ order
 * (63/125/250/500/1k/2k/4k/8k/16k). "Пользовательский" isn't a preset to apply -- it's what the
 * UI shows when the current gains don't match any of these (see matchPreset). */
enum class EqPreset(val label: String, val gainsDb: List<Float>) {
    BALANCED("Сбалансированный", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    MORE_BASS("Больше низких", listOf(7f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f)),
    SOFT("Мягкий", listOf(-1f, -1f, 0f, 1f, 1f, 0f, -1f, -2f, -2f)),
    DYNAMIC("Динамичный", listOf(5f, 4f, 2f, 0f, -2f, 0f, 2f, 4f, 5f)),
    CLEAN("Чистый", listOf(-1f, 0f, 0f, 1f, 2f, 2f, 3f, 3f, 2f)),
    MORE_TREBLE("Больше высоких", listOf(0f, 0f, 0f, 0f, 1f, 2f, 4f, 6f, 7f)),
    ;

    companion object {
        private const val TOLERANCE = 0.05f

        /** Null means "Пользовательский" -- gains the user set by hand, not any fixed preset. */
        fun matching(gainsDb: List<Float>): EqPreset? = entries.firstOrNull { preset ->
            preset.gainsDb.size == gainsDb.size &&
                preset.gainsDb.indices.all { i -> kotlin.math.abs(preset.gainsDb[i] - gainsDb[i]) < TOLERANCE }
        }
    }
}
