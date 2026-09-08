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
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import dev.nami.app.R
import dev.nami.domain.PlaybackState

/** Виджет 3/8 - большой квадрат: обложка на весь фон, полоса прогресса, полный транспорт
 * (prev/play/next). Прогресс - снимок на момент обновления виджета, не тикает сам по себе
 * (Android жёстко ограничивает частоту обновлений RemoteViews, у виджета нет своего таймера). */
class NamiWidgetSquarePlayer : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        val nowPlaying = repo.queue.value.nowPlaying
        val playing = repo.state.value as? PlaybackState.Playing
        val art = loadArtBitmap(nowPlaying?.artworkPath)
        val progress = if (playing != null && playing.durationMs > 0) (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) else 0f

        provideContent {
            Box(modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetBackground)) {
                if (art != null) {
                    Image(provider = ImageProvider(art), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                    Box(modifier = GlanceModifier.fillMaxSize().background(androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color(0x99000000)))) {}
                }
                Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.Vertical.Bottom) {
                    androidx.glance.text.Text(
                        nowPlaying?.title ?: "Ничего не играет",
                        style = androidx.glance.text.TextStyle(color = WidgetTextPrimary, fontSize = 15.sp),
                        maxLines = 1,
                    )
                    nowPlaying?.artistName?.let {
                        androidx.glance.text.Text(it, style = androidx.glance.text.TextStyle(color = WidgetTextSecondary, fontSize = 12.sp), maxLines = 1)
                    }
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.size(10.dp))
                    // Glance has no fillMaxWidth(fraction) overload - width is computed from
                    // LocalSize.current (the actual placed widget size on the home screen).
                    val trackWidth = androidx.glance.LocalSize.current.width - 28.dp
                    Box(modifier = GlanceModifier.width(trackWidth).height(4.dp).then(widgetCorner(2)).background(WidgetTrackEmpty)) {
                        Box(modifier = GlanceModifier.width(trackWidth * progress).height(4.dp).then(widgetCorner(2)).background(WidgetAccent)) {}
                    }
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.size(10.dp))
                    TransportRow(isPlayingNow(repo), iconSize = 26.dp)
                }
            }
        }
    }
}

class NamiWidgetSquarePlayerReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetSquarePlayer()
}
