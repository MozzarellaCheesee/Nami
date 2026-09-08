package dev.nami.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

private val NamiTypography = Typography(
    titleLarge = NamiType.ScreenTitle,
    titleMedium = NamiType.TrackTitle,
    bodyLarge = NamiType.ListTitle,
    bodyMedium = NamiType.Secondary,
    labelMedium = NamiType.TechData,
    labelSmall = NamiType.Caption,
)

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
 * AMOLED") -- отдельный тумблер поверх тёмной темы, не сама тёмная тема. */
@Composable
fun NamiTheme(amoled: Boolean = false, content: @Composable () -> Unit) {
    SideEffect { setAmoledColors(amoled) }
    MaterialTheme(colorScheme = NamiDarkScheme, typography = NamiTypography, content = content)
}
