package dev.nami.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily

private fun buildTypography(uiFont: FontFamily?): Typography {
    fun style(base: androidx.compose.ui.text.TextStyle) = if (uiFont != null) base.copy(fontFamily = uiFont) else base
    return Typography(
        titleLarge = style(NamiType.ScreenTitle),
        titleMedium = style(NamiType.TrackTitle),
        bodyLarge = style(NamiType.ListTitle),
        bodyMedium = style(NamiType.Secondary),
        labelMedium = style(NamiType.TechData),
        labelSmall = style(NamiType.Caption),
    )
}

private val NamiDarkScheme = darkColorScheme(
    background = NamiColors.Ink900,
    surface = NamiColors.Ink800,
    surfaceVariant = NamiColors.Ink700,
    outline = NamiColors.Ink600,
    onBackground = NamiColors.Paper100,
    onSurface = NamiColors.Paper100,
    onSurfaceVariant = NamiColors.Paper70,
    primary = NamiColors.Shu,
    secondary = NamiColors.Ai,
    error = NamiColors.Kin,
)

/** [amoled] заменяет ink-900 на чистый #000000 и поверхности на #0A0B0D (Дизайн.md, "Режим
 * AMOLED") - отдельный тумблер поверх тёмной темы, не сама тёмная тема. [uiFont] - группа E
 * "свой шрифт интерфейса", подменяет Archivo во всех MaterialTheme.typography ролях, null =
 * стандартный. */
@Composable
fun NamiTheme(amoled: Boolean = false, uiFont: FontFamily? = null, content: @Composable () -> Unit) {
    SideEffect { setAmoledColors(amoled) }
    val typography = remember(uiFont) { buildTypography(uiFont) }
    MaterialTheme(colorScheme = NamiDarkScheme, typography = typography, content = content)
}
