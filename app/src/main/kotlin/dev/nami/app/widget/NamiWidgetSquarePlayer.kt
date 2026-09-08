package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.nami.app.R
import dev.nami.domain.PlaybackState
import kotlinx.coroutines.flow.first

/** Квадратный плеер - обложка на весь фон, полоса прогресса, транспорт (prev/play/next) + лайк.
 * Прогресс - снимок на момент обновления виджета, не тикает сам по себе (Android жёстко
 * ограничивает частоту обновлений RemoteViews, у виджета нет своего таймера). */
class NamiWidgetSquarePlayer : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        val nowPlaying = repo.queue.value.nowPlaying
        val playing = repo.state.value as? PlaybackState.Playing
        val art = loadArtBitmap(nowPlaying?.artworkPath)
        val progress = if (playing != null && playing.durationMs > 0) (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) else 0f
        val isLiked = nowPlaying?.let { widgetPlaylistRepository(context).isTrackLiked(it.id).first() } ?: false

        provideContent {
            Box(modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetBackground)) {
                if (art != null) {
                    Image(provider = ImageProvider(art), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                    Box(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xB3000000)))) {}
                }
                Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.Vertical.Bottom) {
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                nowPlaying?.title ?: "Ничего не играет",
                                style = TextStyle(color = WidgetTextPrimary, fontSize = 15.sp),
                                maxLines = 1,
                            )
                            nowPlaying?.artistName?.let {
                                Text(it, style = TextStyle(color = WidgetTextSecondary, fontSize = 12.sp), maxLines = 1, modifier = GlanceModifier.padding(top = 2.dp))
                            }
                        }
                        if (nowPlaying != null) {
                            TransportButton(
                                if (isLiked) R.drawable.ic_widget_like_filled else R.drawable.ic_widget_like_outline,
                                if (isLiked) "Убрать из любимых" else "В любимые",
                                20.dp,
                                actionRunCallback<ToggleLikeAction>(),
                                tint = if (isLiked) WidgetAccent else WidgetTextPrimary,
                            )
                        }
                    }
                    Spacer(modifier = GlanceModifier.size(10.dp))
                    // Glance has no fillMaxWidth(fraction) - width is computed from LocalSize
                    // (the widget's own placed size on the home screen).
                    val trackWidth = LocalSize.current.width - 28.dp
                    Box(modifier = GlanceModifier.width(trackWidth).height(4.dp).then(widgetCorner(2)).background(WidgetTrackEmpty)) {
                        Box(modifier = GlanceModifier.width(trackWidth * progress).height(4.dp).then(widgetCorner(2)).background(WidgetAccent)) {}
                    }
                    Spacer(modifier = GlanceModifier.size(10.dp))
                    TransportRow(isPlayingNow(repo), iconSize = 26.dp)
                }
            }
        }
    }
}

class NamiWidgetSquarePlayerReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetSquarePlayer()
}
