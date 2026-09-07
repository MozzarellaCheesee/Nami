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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        // ModalBottomSheet opens its own separate Android Window (a Dialog), which doesn't
        // inherit MainActivity's immersive (hidden system bars) state -- the nav/status bar
        // popped back in every time this sheet opened. Must run INSIDE this content lambda, not
        // above the ModalBottomSheet call: LocalView.current out there resolves to the ACTIVITY's
        // own view (the dialog doesn't exist yet at that point in composition), so
        // `view.parent as? DialogWindowProvider` was always null and this never actually touched
        // the sheet's window at all -- a real, silent no-op, not just "still buggy".
        // setDecorFitsSystemWindows(false) (matching what enableEdgeToEdge already did on the
        // activity window) stops the sheet's content from reflowing/jumping the instant the nav
        // bar shows; the bar-hiding below then keeps it from showing in the first place, mirroring
        // whatever the activity's own window is currently doing.
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
