package dev.nami.feature.player

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import dev.nami.core.designsystem.NamiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracted swatches skew gray on near-monochrome art - Дизайн.md явно требует форсировать
 * насыщенность, чтобы такие обложки не сводили акцент к серому. Пол по насыщенности, пол/потолок
 * по светлоте - чтобы акцент оставался читаемым, не почти-чёрным и не почти-белым. */
private fun forceSaturation(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = hsl[1].coerceAtLeast(0.45f)
    hsl[2] = hsl[2].coerceIn(0.35f, 0.65f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * The dominant/vibrant color of the track artwork at [artworkPath], decoded off the main
 * thread via Palette. Falls back to the default accent (--shu) while loading, on a null path,
 * or if decoding/Palette fails for any reason (corrupt file, OOM on a huge image, etc).
 */
@Composable
fun rememberArtworkAccentColor(artworkPath: String?): Color {
    val state = produceState(initialValue = NamiColors.Shu, key1 = artworkPath) {
        value = artworkPath?.let { extractAccentColor(it) } ?: NamiColors.Shu
    }
    return state.value
}

private suspend fun extractAccentColor(path: String): Color = withContext(Dispatchers.IO) {
    try {
        // Downsample aggressively - this only feeds a color average, not a display image.
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext NamiColors.Shu
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
        bitmap.recycle()
        swatch?.let { forceSaturation(Color(it.rgb)) } ?: NamiColors.Shu
    } catch (e: Exception) {
        NamiColors.Shu
    }
}
