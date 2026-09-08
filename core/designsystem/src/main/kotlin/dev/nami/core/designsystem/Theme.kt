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

/** Не top-level val (было бы посчитано один раз при загрузке класса) - вызывается внутри
 * [NamiTheme] на каждой рекомпозиции, чтобы редактор тем (П.md §26) реально перекрашивал
 * MaterialTheme.colorScheme-based элементы (Switch, всё остальное на NamiColors.* и так уже
 * реактивно само по себе). */
private fun namiDarkScheme() = darkColorScheme(
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
 * стандартный. [colorOverrides] - П.md §26 "Редактор темы", токен -> hex ("#RRGGBB"/"#AARRGGBB"),
 * применяется через NamiColors.setOverride перед первой отрисовкой контента. */
@Composable
fun NamiTheme(amoled: Boolean = false, uiFont: FontFamily? = null, colorOverrides: Map<String, String> = emptyMap(), content: @Composable () -> Unit) {
    SideEffect {
        setAmoledColors(amoled)
        NamiColors.EDITABLE_TOKENS.forEach { token ->
            val hex = colorOverrides[token]
            NamiColors.setOverride(token, if (hex != null) runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex)) }.getOrNull() else null)
        }
    }
    val typography = remember(uiFont) { buildTypography(uiFont) }
    MaterialTheme(colorScheme = namiDarkScheme(), typography = typography, content = content)
}
