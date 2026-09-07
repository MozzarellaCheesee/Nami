package dev.nami.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

data class ContextAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Slide-up sheet for a "..." menu -- the app-wide replacement for a plain
 * [androidx.compose.material3.DropdownMenu]. Each target type (track/album/playlist/artist)
 * supplies its own [actions] list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextActionSheet(onDismiss: () -> Unit, actions: List<ContextAction>) {
    // ModalBottomSheet opens its own separate Android Window (a Dialog), which doesn't inherit
    // MainActivity's immersive (hidden system bars) state -- the nav/status bar used to pop back
    // in every time this sheet opened, AND (the part that actually caused the visible "jump")
    // that dialog window defaults to decorFitsSystemWindows(true): the instant the nav bar shows,
    // the system resizes/insets the dialog's own content to sit above it, so the sheet -- and by
    // extension everything anchored relative to it, like NowPlayingScreen's transport row sitting
    // right above the sheet's own top edge -- visibly shifts upward for that one frame.
    // setDecorFitsSystemWindows(false) here (matching what enableEdgeToEdge already did on the
    // activity window) stops that reflow from ever happening, regardless of bar visibility; the
    // bar-hiding below is then just a cosmetic match, not load-bearing for the jump anymore.
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = view.context.findActivity()?.window
        if (dialogWindow != null && activityWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            val activityBarsHidden = ViewCompat.getRootWindowInsets(activityWindow.decorView)
                ?.isVisible(WindowInsetsCompat.Type.systemBars()) == false
            if (activityBarsHidden) {
                val controller = WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); action.onClick() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null, tint = NamiColors.Paper100)
                    Text(
                        text = action.label,
                        color = NamiColors.Paper100,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
            }
        }
    }
}
