package dev.nami.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Call once inside ANY ModalBottomSheet/Dialog's own content lambda (must be inside it, not
 * above the ModalBottomSheet/Dialog call - see the inline comment below for why that placement
 * is load-bearing). Every sheet in the app needs this same fix, so it's factored out here instead
 * of copy-pasted into each one - ContextActionSheet had it duplicated before, and the two sheets
 * added afterwards (Аудиотракт, Таймер сна) were never given the same fix, so the system bars
 * popped back in and shoved their content around exactly like before this existed.
 *
 * ModalBottomSheet/Dialog opens its own separate Android Window, which doesn't inherit
 * MainActivity's immersive (hidden system bars) state - the nav/status bar pops back in every
 * time a sheet opens, and (the part that actually shoves content around) that window defaults to
 * decorFitsSystemWindows(true): the instant the nav bar shows, the system resizes/insets the
 * sheet's own content to sit above it, visibly shifting everything up for that one frame.
 * setDecorFitsSystemWindows(false) (matching what enableEdgeToEdge already did on the activity
 * window) stops that reflow outright; the bar-hiding on top of it then keeps the bar from
 * showing in the first place, mirroring whatever the activity's own window is currently doing.
 */
@Composable
fun ImmersiveSheetEffect() {
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
}
