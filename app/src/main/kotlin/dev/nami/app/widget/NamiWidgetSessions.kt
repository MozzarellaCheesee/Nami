package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.nami.domain.Session

private const val MAX_SESSION_PILLS = 3
val SessionNameKey = androidx.glance.action.ActionParameters.Key<String>("session_name")

class ApplySessionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val name = parameters[SessionNameKey] ?: return
        val settings = widgetSettingsRepository(context)
        val session = settings.sessions.value.firstOrNull { it.name == name } ?: return
        settings.setEqBandGains(session.eqGainsDb)
        settings.setEqEnabled(true)
        settings.setCrossfadeEnabled(session.crossfadeEnabled)
        session.sleepTimerMinutes?.let { minutes ->
            widgetPlayerRepository(context).awaitReady()
            widgetPlayerRepository(context).startSleepTimer(minutes * 60_000L)
        }
        // Session сама по себе не хранит "применена ли сейчас" - без этого нажатие пилюли не
        // давало никакого видимого отклика (реально применялось, но выглядело как ничего не
        // произошло).
        settings.setLastAppliedSessionName(name)
        NamiWidgetSessions().updateAll(context)
    }
}

/** Виджет 8/8 - до 3 пилюль с именами сохранённых Сессий (План.md §22.11), тап применяет
 * EQ/кроссфейд/таймер сна той сессии - те же самые Сессии, что в Настройки -> Плеер -> Сессии,
 * не отдельный виджетный список. Пусто, если пользователь ещё ни одной не сохранил. */
class NamiWidgetSessions : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = widgetSettingsRepository(context)
        val sessions = settings.sessions.value.take(MAX_SESSION_PILLS)
        val activeName = settings.lastAppliedSessionName.value

        provideContent {
            Box(modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetBackground).padding(8.dp), contentAlignment = Alignment.Center) {
                if (sessions.isEmpty()) {
                    Text("Нет сохранённых сессий", style = TextStyle(color = WidgetTextSecondary, fontSize = 12.sp))
                } else {
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        sessions.forEachIndexed { index, session ->
                            if (index > 0) androidx.glance.layout.Spacer(modifier = GlanceModifier.size(8.dp))
                            SessionPill(session, isActive = session.name == activeName)
                        }
                    }
                }
            }
        }
    }
}

/** [isActive] - последняя применённая сессия (см. SettingsRepository.lastAppliedSessionName)
 * подсвечивается акцентным фоном, чтобы тап давал видимый результат - до этого пилюля выглядела
 * одинаково и до, и после нажатия, даже когда EQ/кроссфейд реально применились. */
@androidx.compose.runtime.Composable
private fun SessionPill(session: Session, isActive: Boolean) {
    Box(
        modifier = GlanceModifier
            .then(widgetCorner(16))
            .background(if (isActive) WidgetAccent else WidgetSurface)
            .clickable(actionRunCallback<ApplySessionAction>(actionParametersOf(SessionNameKey to session.name)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(session.name, style = TextStyle(color = WidgetTextPrimary, fontSize = 13.sp), maxLines = 1)
    }
}

class NamiWidgetSessionsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetSessions()
}
