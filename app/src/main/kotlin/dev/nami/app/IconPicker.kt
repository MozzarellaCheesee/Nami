package dev.nami.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Launcher icon picker (Settings) -- each entry is an `<activity-alias>` in the manifest
 * targeting MainActivity with its own `android:icon`, exactly one enabled at a time. Toggling
 * which alias is enabled is what actually changes the icon shown on the home screen/app drawer;
 * there's no other public API for changing an already-installed app's icon at runtime. */
enum class LauncherIcon(val aliasName: String, val label: String, val previewRes: Int) {
    WAVE("IconWave", "Волна", R.mipmap.ic_launcher),
    KANJI("IconKanji", "Кандзи", R.drawable.ic_launcher_kanji),
    MINIMAL_WHITE("IconMinimalWhite", "Минимал светлый", R.drawable.ic_launcher_minimal_white),
    MINIMAL_BLACK("IconMinimalBlack", "Минимал тёмный", R.drawable.ic_launcher_minimal_black),
    MINIMAL_RED("IconMinimalRed", "Минимал красный", R.drawable.ic_launcher_minimal_red),
    BLACK("IconBlack", "Полный", R.drawable.ic_launcher_black),
}

object IconPicker {
    private fun componentName(context: Context, icon: LauncherIcon) =
        ComponentName(context.packageName, "${context.packageName}.${icon.aliasName}")

    /** Falls back to [LauncherIcon.WAVE] (the manifest's default-enabled alias) when nothing has
     * ever been explicitly toggled -- getComponentEnabledSetting reports DEFAULT, not ENABLED,
     * until setComponentEnabledSetting is called at least once. */
    fun current(context: Context): LauncherIcon {
        val pm = context.packageManager
        return LauncherIcon.entries.firstOrNull { icon ->
            pm.getComponentEnabledSetting(componentName(context, icon)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: LauncherIcon.WAVE
    }

    /** Enables [icon]'s alias and disables every other one -- exactly one is ever active, so the
     * launcher never ends up showing zero or two icons for the app. This restarts the launcher's
     * view of the app (a brief icon-changed animation on most launchers), not the app itself. */
    fun select(context: Context, icon: LauncherIcon) {
        val pm = context.packageManager
        LauncherIcon.entries.forEach { candidate ->
            val state = if (candidate == icon) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(componentName(context, candidate), state, PackageManager.DONT_KILL_APP)
        }
    }
}
