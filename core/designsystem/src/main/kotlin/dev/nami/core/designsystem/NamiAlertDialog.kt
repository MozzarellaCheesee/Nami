package dev.nami.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * [AlertDialog] restyled to match the app's own tokens instead of Material3's stock look --
 * r16 (per Дизайн.md's "карточка, поле" radius, not Material's default 28dp pill-ish corner) and
 * no tonal elevation overlay (Material3 otherwise tints the container a shade lighter than
 * `surface`, drifting from the exact Ink800 hex). Colors otherwise already come from NamiTheme's
 * dark color scheme (surface/onSurface/primary already map to Ink800/Paper100/Shu), so this is
 * the styling gap, not a full reimplementation.
 */
@Composable
fun NamiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(16.dp),
        containerColor = NamiColors.Ink800,
        tonalElevation = 0.dp,
        titleContentColor = NamiColors.Paper100,
        textContentColor = NamiColors.Paper70,
    )
}
