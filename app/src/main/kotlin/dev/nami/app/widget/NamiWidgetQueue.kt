package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.nami.app.R

private const val MAX_QUEUE_ROWS = 4

/** Виджет 5/8 - обложка играющего трека слева, список следующих треков очереди справа. */
class NamiWidgetQueue : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        val queue = repo.queue.value
        val art = loadArtBitmap(queue.nowPlaying?.artworkPath)
        val upcomingTitles = queue.upcoming.take(MAX_QUEUE_ROWS).map { it.track.title }

        provideContent {
            Row(
                modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetBackground)
                    .clickable(actionStartActivity(openPlayerIntent(context)))
                    .padding(12.dp),
            ) {
                Box(modifier = GlanceModifier.fillMaxHeight().width(96.dp).then(widgetCorner(12)).background(WidgetSurface)) {
                    if (art != null) {
                        Image(provider = ImageProvider(art), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                    } else {
                        Image(provider = ImageProvider(R.drawable.ic_launcher_wave), contentDescription = null, modifier = GlanceModifier.fillMaxSize().padding(16.dp))
                    }
                }
                androidx.glance.layout.Spacer(modifier = GlanceModifier.width(12.dp))
                Column {
                    if (upcomingTitles.isEmpty()) {
                        Text("Очередь пуста", style = TextStyle(color = WidgetTextSecondary, fontSize = 13.sp))
                    } else {
                        upcomingTitles.forEach { title ->
                            Text(
                                title,
                                style = TextStyle(color = WidgetTextPrimary, fontSize = 13.sp),
                                maxLines = 1,
                                modifier = GlanceModifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

class NamiWidgetQueueReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetQueue()
}
