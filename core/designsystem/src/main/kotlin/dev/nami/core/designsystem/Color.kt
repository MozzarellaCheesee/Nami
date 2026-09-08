package dev.nami.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Set by [NamiTheme] from the persisted AMOLED setting. A plain observable flag rather than a
 * CompositionLocal so the many existing call sites (`NamiColors.Ink900` used directly, not via
 * MaterialTheme.colorScheme) keep working unchanged and still recompose on toggle -- this is a
 * single-activity app, one global flag is enough.
 * ponytail: global flag, switch to a CompositionLocal if this ever needs to vary per-window. */
private var amoledEnabled by mutableStateOf(false)

internal fun setAmoledColors(enabled: Boolean) {
    amoledEnabled = enabled
}

object NamiColors {
    val Ink900: Color get() = if (amoledEnabled) AmoledBackground else Color(0xFF0C0D0F)
    val Ink800: Color get() = if (amoledEnabled) AmoledSurface else Color(0xFF131417)
    val AmoledBackground = Color(0xFF000000)
    val AmoledSurface = Color(0xFF0A0B0D)
    val Ink700 = Color(0xFF1A1B1F)
    val Ink600 = Color(0xFF232429)
    val Ink500 = Color(0xFF3A3C43)
    val Paper100 = Color(0xFFEDEAE4)
    val Paper70 = Color(0xFF9B9A97)
    val Paper40 = Color(0xFF7A7C82)
    val Shu = Color(0xFFC24A34)
    val Ai = Color(0xFF6A8CC0)
    val Kin = Color(0xFFC9A227)
    val Wakaba = Color(0xFF5FA463)
}
