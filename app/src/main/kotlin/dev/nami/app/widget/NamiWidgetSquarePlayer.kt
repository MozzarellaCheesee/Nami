package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
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

/** Квадратный виджет плеера: фоновая обложка, название, артист, прогресс и транспорт.
 * Адаптируется под квадратные (2x2, 3x3, 4x4) и растянутые (3x2, 4x2) размеры. */
class NamiWidgetSquarePlayer : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(TINY, HORIZONTAL, SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        repo.awaitReady()
        val nowPlaying = repo.queue.value.nowPlaying
        val playing = repo.state.value as? PlaybackState.Playing
        val art = loadArtBitmap(nowPlaying?.artworkPath)
        val progress = if (playing != null && playing.durationMs > 0) (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) else 0f
        val isLiked = nowPlaying?.let { widgetPlaylistRepository(context).isTrackLiked(it.id).first() } ?: false
        val isShuffle = repo.shuffleEnabled.value
        val openPlayerAction = actionStartActivity(openPlayerIntent(context))

        provideContent {
            val size = LocalSize.current
            val isCompactHeight = size.height < 155.dp
            val padding = if (isCompactHeight || size.width < MEDIUM.width) 10.dp else 14.dp
            val gap = if (isCompactHeight) 4.dp else (if (size.height < MEDIUM.height) 6.dp else 10.dp)
            val showShuffle = size.width >= 240.dp

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .then(widgetCorner())
                    .background(WidgetBackground)
                    .clickable(openPlayerAction),
            ) {
                if (art != null) {
                    Image(
                        provider = ImageProvider(art),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                    Box(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xB3000000)))) {}
                }
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalAlignment = Alignment.Vertical.Bottom,
                ) {
                    Row(
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                        modifier = GlanceModifier.clickable(openPlayerAction),
                    ) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                nowPlaying?.title ?: "Ничего не играет",
                                style = TextStyle(
                                    color = WidgetTextPrimary,
                                    fontSize = if (isCompactHeight) 13.sp else 15.sp,
                                ),
                                maxLines = 1,
                            )
                            val subtitle = nowPlaying?.artistName ?: if (nowPlaying == null) "Нажмите, чтобы открыть Nami" else null
                            subtitle?.let {
                                Text(
                                    it,
                                    style = TextStyle(color = WidgetTextSecondary, fontSize = 11.sp),
                                    maxLines = 1,
                                    modifier = GlanceModifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (nowPlaying != null) {
                            TransportButton(
                                if (isLiked) R.drawable.ic_widget_like_filled else R.drawable.ic_widget_like_outline,
                                if (isLiked) "Убрать из любимых" else "В любимые",
                                if (isCompactHeight) 18.dp else 20.dp,
                                actionRunCallback<ToggleLikeAction>(),
                                tint = if (isLiked) WidgetAccent else WidgetTextPrimary,
                            )
                        }
                    }
                    Spacer(modifier = GlanceModifier.size(gap))
                    val trackWidth = (size.width - padding * 2).coerceAtLeast(40.dp)
                    Box(modifier = GlanceModifier.width(trackWidth).height(4.dp).then(widgetCorner(2)).background(WidgetTrackEmpty)) {
                        Box(modifier = GlanceModifier.width(trackWidth * progress).height(4.dp).then(widgetCorner(2)).background(WidgetAccent)) {}
                    }
                    Spacer(modifier = GlanceModifier.size(gap))
                    val transportIconSize = (size.width.value / 180f * 24f).dp.coerceIn(22.dp, 40.dp)
                    TransportRow(
                        isPlaying = isPlayingNow(repo),
                        iconSize = if (isCompactHeight) 22.dp else transportIconSize,
                        showShuffle = showShuffle,
                        isShuffle = isShuffle,
                    )
                }
            }
        }
    }

    private companion object {
        val TINY = DpSize(140.dp, 140.dp)
        val HORIZONTAL = DpSize(240.dp, 130.dp)
        val SMALL = DpSize(180.dp, 180.dp)
        val MEDIUM = DpSize(250.dp, 250.dp)
        val LARGE = DpSize(320.dp, 320.dp)
    }
}

class NamiWidgetSquarePlayerReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetSquarePlayer()
}
