package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import dev.nami.app.R

/** Виджет 1/8 (макет 4.15, верхний левый) - маленький квадрат: обложка + play/next. */
class NamiWidgetCompact : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        val nowPlaying = repo.queue.value.nowPlaying
        val isPlaying = isPlayingNow(repo)
        val art = loadArtBitmap(nowPlaying?.artworkPath)

        provideContent {
            Box(modifier = GlanceModifier.fillMaxSize().background(WidgetBackground).then(widgetCorner()).padding(10.dp)) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Box(modifier = GlanceModifier.size(44.dp).then(widgetCorner(10)).background(WidgetSurface)) {
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
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.size(10.dp))
                    // Только play/next - на 1x1 виджете нет места на все три кнопки транспорта.
                    TransportButton(
                        if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        if (isPlaying) "Пауза" else "Играть",
                        22.dp,
                        androidx.glance.appwidget.action.actionRunCallback<TogglePlaybackAction>(),
                    )
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.size(8.dp))
                    TransportButton(R.drawable.ic_widget_next, "Следующий", 22.dp, androidx.glance.appwidget.action.actionRunCallback<SkipNextAction>())
                }
            }
        }
    }
}

class NamiWidgetCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetCompact()
}
