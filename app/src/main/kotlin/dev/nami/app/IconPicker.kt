package dev.nami.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Launcher icon picker (Settings) - each entry is an `<activity-alias>` in the manifest
 * targeting MainActivity with its own `android:icon`, exactly one enabled at a time. Toggling
 * which alias is enabled is what actually changes the icon shown on the home screen/app drawer;
 * there's no other public API for changing an already-installed app's icon at runtime. */
enum class LauncherIcon(val aliasName: String, val label: String, val previewRes: Int) {
    WAVE("IconWave", "Волна", R.drawable.ic_launcher_wave),
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
     * ever been explicitly toggled - getComponentEnabledSetting reports DEFAULT, not ENABLED,
     * until setComponentEnabledSetting is called at least once. */
    fun current(context: Context): LauncherIcon {
        val pm = context.packageManager
        return LauncherIcon.entries.firstOrNull { icon ->
            pm.getComponentEnabledSetting(componentName(context, icon)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: LauncherIcon.WAVE
    }

    /** Enables [icon]'s alias and disables every other one - exactly one is ever active, so the
     * launcher never ends up showing zero or two icons for the app.
     *
     * Enable the new one BEFORE disabling the old one (never a moment with zero enabled aliases,
     * which some launchers read as "app uninstalled" and drop the icon from the home screen
     * entirely until a manual re-add). Always DONT_KILL_APP - killing the process to force a
     * launcher's icon cache to refresh also kills playback (MediaSessionService dies with it),
     * which is worse than a stale icon until the launcher notices on its own or the user
     * force-stops/re-adds the shortcut by hand. */
    fun select(context: Context, icon: LauncherIcon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(componentName(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        LauncherIcon.entries.filter { it != icon }.forEach { candidate ->
            pm.setComponentEnabledSetting(componentName(context, candidate), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        pendingRefresh = icon
    }

    /** П.md §27, жалоба "на Samsung иконка не меняется". Лаунчер OneUI кеширует иконку и не
     * перечитывает её, пока наше приложение - активная задача на экране: смена алиасов
     * происходит ровно в тот момент, когда лаунчер на нас не смотрит вообще.
     *
     * Явная рассылка ACTION_PACKAGE_CHANGED, которую советуют в интернете как обходной путь,
     * НЕ работает и работать не может: этот action в AOSP объявлен protected-broadcast, обычное
     * приложение при sendBroadcast получает SecurityException, а не обновление иконки. Из того,
     * что приложению вообще доступно, остаётся одно - переключить состояние ещё раз в момент,
     * когда мы уходим в фон, чтобы система разослала свой (настоящий, системный) PACKAGE_CHANGED,
     * пока лаунчер выходит на передний план и заново читает компоненты пакета.
     *
     * Гасим и включаем именно выбранный алиас: PackageManager не рассылает ничего, если состояние
     * не изменилось, поэтому повторная установка того же ENABLED была бы пустой операцией.
     * Короткое окно "ни один алиас не включён" между двумя вызовами прикрыто [ensureValidState]
     * на старте - оно же прикрывает падение ровно в этот момент.
     *
     * Не проверено на живом Samsung - ни у автора правки, ни у пользователя в этот заход
     * устройства не было. */
    fun refreshLauncherIfPending(context: Context) {
        val icon = pendingRefresh ?: return
        pendingRefresh = null
        val pm = context.packageManager
        val component = componentName(context, icon)
        pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }

    /** Живёт в памяти процесса, а не в настройках: если процесс умер, немедленное переключение
     * в [select] уже случилось и повторять его на следующем старте незачем. */
    private var pendingRefresh: LauncherIcon? = null

    /** Self-heal for "every alias somehow ended up disabled" (should never happen given [select]
     * always enables one before disabling the rest, but a crash mid-toggle or a manifest change
     * across an app update could still leave it in that state) - with zero aliases enabled the
     * app has no launcher icon at all, silently vanishing from the home screen/app drawer.
     * Call once at process startup. */
    fun ensureValidState(context: Context) {
        val pm = context.packageManager
        val anyEnabled = LauncherIcon.entries.any { icon ->
            pm.getComponentEnabledSetting(componentName(context, icon)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        // WAVE relies on the manifest's own enabled="true" default until first explicitly
        // toggled - that's DEFAULT, not ENABLED, and is the normal, healthy state on a fresh
        // install, not something to "fix". Only WAVE explicitly disabled with nothing else
        // enabled is the actual broken state this guards against.
        val waveExplicitlyDisabled = pm.getComponentEnabledSetting(componentName(context, LauncherIcon.WAVE)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (!anyEnabled && waveExplicitlyDisabled) {
            select(context, LauncherIcon.WAVE)
        }
    }
}
