package dev.nami.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun NamiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NamiDarkScheme, content = content)
}
