package dev.nami.player

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Glance-виджеты живут в :app, а состояние плеера - здесь, и :app зависит от :player, а не
 * наоборот, так что напрямую вызвать updateAll() нельзя. Системный ACTION_APPWIDGET_UPDATE по
 * имени класса-ресивера - тот же приём по строковому имени, что уже используется для плиток
 * (см. requestTileListening в PlayerRepositoryImpl). */
private val WIDGET_RECEIVERS = listOf(
    "dev.nami.app.widget.NamiWidgetCompactReceiver",
    "dev.nami.app.widget.NamiWidgetSquarePlayerReceiver",
    "dev.nami.app.widget.NamiWidgetSessionsReceiver",
)

/** Просит все виджеты Nami перерисоваться. Без этого виджет обновлялся только при нажатии его
 * собственной кнопки и по updatePeriodMillis (полчаса) - смена трека/пауза/лайк из приложения или
 * из шторки на рабочий стол не доезжали. */
fun nudgeNamiWidgets(context: Context) {
    val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
    WIDGET_RECEIVERS.forEach { className ->
        val component = ComponentName(context.packageName, className)
        // Пусто, если пользователь этот виджет не добавлял - широковещалка без id ничего не делает.
        val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return@forEach
        if (ids.isEmpty()) return@forEach
        runCatching {
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    this.component = component
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
