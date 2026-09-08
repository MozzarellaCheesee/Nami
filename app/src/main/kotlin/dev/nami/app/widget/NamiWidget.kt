package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.nami.app.R
import dev.nami.domain.PlaybackState

private val WidgetBackground = ColorProvider(Color(0xFF0C0D0F))
private val WidgetTextPrimary = ColorProvider(Color(0xFFEDEAE4))
private val WidgetTextSecondary = ColorProvider(Color(0xFF9B9A97))

/** Группа E "виджеты" - минимальный жизнеспособный виджет: текущий трек + play/pause/next.
 * Читает состояние прямо из singleton PlayerRepository (тот же процесс), без своего IPC -
 * если процесс приложения не жив, покажет "Ничего не играет" вместо последнего трека (честное
 * ограничение, не пытался кэшировать состояние отдельно на такой случай). Обновляется сам себя
 * после каждого нажатия своих же кнопок - живого пуша при смене трека из самого приложения нет
 * (Android жёстко ограничивает частоту обновлений виджетов), следующий шаг той же задачи. */
class NamiWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        val nowPlaying = repo.queue.value.nowPlaying
        val isPlaying = (repo.state.value as? PlaybackState.Playing)?.isPlaying == true

        provideContent {
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(72.dp).background(WidgetBackground).padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_launcher_wave),
                    contentDescription = null,
                    modifier = GlanceModifier.size(48.dp),
                )
                Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 12.dp)) {
                    Text(nowPlaying?.title ?: "Ничего не играет", style = TextStyle(color = WidgetTextPrimary))
                    nowPlaying?.artistName?.let { Text(it, style = TextStyle(color = WidgetTextSecondary)) }
                }
                Image(
                    provider = ImageProvider(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    contentDescription = if (isPlaying) "Пауза" else "Играть",
                    modifier = GlanceModifier.size(32.dp).clickable(actionRunCallback<TogglePlaybackAction>()),
                )
                Image(
                    provider = ImageProvider(android.R.drawable.ic_media_next),
                    contentDescription = "Следующий",
                    modifier = GlanceModifier.size(32.dp).padding(start = 8.dp).clickable(actionRunCallback<SkipNextAction>()),
                )
            }
        }
    }
}

class NamiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidget()
}

class TogglePlaybackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        widgetPlayerRepository(context).toggle()
        NamiWidget().update(context, glanceId)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        widgetPlayerRepository(context).skipNext()
        NamiWidget().update(context, glanceId)
    }
}
