package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.nami.app.R

/** Виджет 2/8 - пилюля с названием играющего трека, тап открывает Now Playing. */
class NamiWidgetPill : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        val nowPlaying = repo.queue.value.nowPlaying

        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(WidgetSurface).then(widgetCorner(28))
                    .clickable(actionStartActivity(openPlayerIntent(context)))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Box(modifier = GlanceModifier.size(20.dp).then(widgetCorner(6)).background(WidgetTrackEmpty)) {
                        Image(provider = ImageProvider(R.drawable.ic_launcher_wave), contentDescription = null, modifier = GlanceModifier.fillMaxSize().padding(3.dp))
                    }
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.size(10.dp))
                    Text(
                        nowPlaying?.title ?: "Ничего не играет",
                        style = TextStyle(color = WidgetTextPrimary),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class NamiWidgetPillReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetPill()
}
