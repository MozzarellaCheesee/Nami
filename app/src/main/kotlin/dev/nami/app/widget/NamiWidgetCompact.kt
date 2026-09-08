package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.nami.app.R

/** Компакт - маленький квадрат: обложка + название/исполнитель + prev/play/next. */
class NamiWidgetCompact : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        // Холодный старт (Android часто убивает процесс приложения в фоне, тап по виджету
        // поднимает его заново) - без ожидания MediaController'а nowPlaying/isPlaying были бы
        // пустыми даже когда реально что-то играет.
        repo.awaitReady()
        val nowPlaying = repo.queue.value.nowPlaying
        val isPlaying = isPlayingNow(repo)
        val art = loadArtBitmap(nowPlaying?.artworkPath)

        provideContent {
            Box(modifier = GlanceModifier.fillMaxSize().background(WidgetBackground).then(widgetCorner()).padding(10.dp)) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Box(modifier = GlanceModifier.size(40.dp).then(widgetCorner(10)).background(WidgetSurface)) {
                        if (art != null) {
                            Image(provider = ImageProvider(art), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                        } else {
                            Image(
                                provider = ImageProvider(R.drawable.ic_launcher_wave),
                                contentDescription = null,
                                modifier = GlanceModifier.fillMaxSize().padding(8.dp),
                            )
                        }
                    }
                    Spacer(modifier = GlanceModifier.size(8.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            nowPlaying?.title ?: "Ничего не играет",
                            style = TextStyle(color = WidgetTextPrimary, fontSize = 13.sp),
                            maxLines = 1,
                        )
                        nowPlaying?.artistName?.let {
                            Text(it, style = TextStyle(color = WidgetTextSecondary, fontSize = 11.sp), maxLines = 1)
                        }
                    }
                    Spacer(modifier = GlanceModifier.size(6.dp))
                    TransportButton(R.drawable.ic_widget_prev, "Предыдущий", 18.dp, actionRunCallback<SkipPreviousAction>())
                    Spacer(modifier = GlanceModifier.size(4.dp))
                    TransportButton(
                        if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        if (isPlaying) "Пауза" else "Играть",
                        18.dp,
                        actionRunCallback<TogglePlaybackAction>(),
                    )
                    Spacer(modifier = GlanceModifier.size(4.dp))
                    TransportButton(R.drawable.ic_widget_next, "Следующий", 18.dp, actionRunCallback<SkipNextAction>())
                }
            }
        }
    }
}

class NamiWidgetCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetCompact()
}
