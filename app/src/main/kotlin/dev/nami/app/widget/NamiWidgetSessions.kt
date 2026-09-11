package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nami.domain.Session

val SessionNameKey = androidx.glance.action.ActionParameters.Key<String>("session_name")

class ApplySessionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val name = parameters[SessionNameKey] ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching {
                val settings = widgetSettingsRepository(context)
                val session = settings.sessions.value.firstOrNull { it.name == name } ?: return@runCatching
                settings.setEqBandGains(session.eqGainsDb)
                settings.setEqEnabled(true)
                settings.setCrossfadeEnabled(session.crossfadeEnabled)
                val playerRepo = widgetPlayerRepository(context)
                playerRepo.awaitReady()
                playerRepo.setShuffleEnabled(session.shuffleEnabled)
                playerRepo.setRepeatMode(session.repeatMode)
                session.sleepTimerMinutes?.let { minutes ->
                    playerRepo.startSleepTimer(minutes * 60_000L)
                }
                settings.setLastAppliedSessionName(name)
            }
        }
        kotlinx.coroutines.delay(100)
        updateAllNamiWidgets(context)
    }
}

/** Виджет быстрого переключения сессий (эквалайзер, кроссфейд, таймер сна).
 * Адаптируется под ширину виджета (от 2 до 5 пилюль).
 * При отсутствии сохранённых сессий клик ведёт в настройки для их создания. */
class NamiWidgetSessions : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, WIDE, EXTRA_WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = widgetSettingsRepository(context)
        val openSettingsAction = actionStartActivity(openSessionsIntent(context))

        provideContent {
            val allSessions by settings.sessions.collectAsState()
            val activeName by settings.lastAppliedSessionName.collectAsState()

            val width = LocalSize.current.width
            val maxPills = when {
                width >= EXTRA_WIDE.width -> 5
                width >= WIDE.width -> 4
                width >= MEDIUM.width -> 3
                else -> 2
            }
            val sessions = allSessions.take(maxPills)

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .then(widgetCorner())
                    .background(WidgetBackground)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (sessions.isEmpty()) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .then(widgetCorner(14))
                            .background(WidgetSurface)
                            .clickable(openSettingsAction)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Нажмите для настройки сессий",
                            style = TextStyle(color = WidgetTextSecondary, fontSize = 12.sp),
                            maxLines = 1,
                        )
                    }
                } else {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                    ) {
                        sessions.forEachIndexed { index, session ->
                            if (index > 0) Spacer(modifier = GlanceModifier.size(4.dp))
                            SessionPill(
                                session = session,
                                isActive = session.name == activeName,
                                modifier = GlanceModifier.defaultWeight(),
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val SMALL = DpSize(120.dp, 40.dp)
        val MEDIUM = DpSize(200.dp, 40.dp)
        val WIDE = DpSize(280.dp, 40.dp)
        val EXTRA_WIDE = DpSize(360.dp, 40.dp)
    }
}

/** [isActive] - последняя применённая сессия подсвечивается акцентным фоном. */
@androidx.compose.runtime.Composable
private fun SessionPill(session: Session, isActive: Boolean, modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier = modifier
            .then(widgetCorner(14))
            .background(if (isActive) WidgetAccent else WidgetSurface)
            .clickable(actionRunCallback<ApplySessionAction>(actionParametersOf(SessionNameKey to session.name)))
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(session.name, style = TextStyle(color = WidgetTextPrimary, fontSize = 12.sp), maxLines = 1)
    }
}

class NamiWidgetSessionsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetSessions()
}
