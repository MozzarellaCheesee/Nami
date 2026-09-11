package dev.nami.app.widget

import android.content.Context
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.nami.app.R
import kotlinx.coroutines.flow.first

/** Компактный виджет плеера: обложка + название/исполнитель + управление.
 *
 * SizeMode.Responsive с 5 ключевыми брейкпоинтами (узкий, обычный, широкий, высокий компакт,
 * высокий большой). При клике по обложке или тексту открывается экран воспроизведения. */
class NamiWidgetCompact : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(MINI, COMPACT, WIDE, TALL_SMALL, TALL_LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = widgetPlayerRepository(context)
        repo.awaitReady()
        val nowPlaying = repo.queue.value.nowPlaying
        val isPlaying = isPlayingNow(repo)
        val isLiked = nowPlaying?.let { widgetPlaylistRepository(context).isTrackLiked(it.id).first() } ?: false
        val art = loadArtBitmap(nowPlaying?.artworkPath)
        val openPlayerAction = actionStartActivity(openPlayerIntent(context))

        provideContent {
            val size = LocalSize.current
            val tall = size.height >= 95.dp
            val narrow = size.width < 180.dp
            val wide = size.width >= 260.dp
            val artSize = if (tall) (if (size.height >= 140.dp) 80.dp else 60.dp) else (if (wide) 48.dp else 40.dp)

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetBackground)
                    .then(widgetCorner())
                    .padding(10.dp)
            ) {
                if (tall) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Art(art, artSize, modifier = GlanceModifier.clickable(openPlayerAction))
                        Spacer(modifier = GlanceModifier.size(6.dp))
                        Column(
                            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                            modifier = GlanceModifier.clickable(openPlayerAction),
                        ) {
                            TrackText(nowPlaying?.title, nowPlaying?.artistName)
                        }
                        Spacer(modifier = GlanceModifier.size(8.dp))
                        TransportRow(
                            isPlaying = isPlaying,
                            iconSize = 22.dp,
                            showLike = wide || size.height >= 140.dp,
                            isLiked = isLiked,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Art(art, artSize, modifier = GlanceModifier.clickable(openPlayerAction))
                        Spacer(modifier = GlanceModifier.size(8.dp))
                        Column(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .clickable(openPlayerAction),
                        ) {
                            TrackText(nowPlaying?.title, nowPlaying?.artistName)
                        }
                        Spacer(modifier = GlanceModifier.size(6.dp))
                        if (wide && nowPlaying != null) {
                            TransportButton(
                                if (isLiked) R.drawable.ic_widget_like_filled else R.drawable.ic_widget_like_outline,
                                if (isLiked) "Убрать из любимых" else "В любимые",
                                18.dp,
                                actionRunCallback<ToggleLikeAction>(),
                                tint = if (isLiked) WidgetAccent else WidgetTextPrimary,
                            )
                            Spacer(modifier = GlanceModifier.size(4.dp))
                        }
                        if (!narrow) {
                            TransportButton(R.drawable.ic_widget_prev, "Предыдущий", 18.dp, actionRunCallback<SkipPreviousAction>())
                            Spacer(modifier = GlanceModifier.size(4.dp))
                        }
                        TransportButton(
                            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                            if (isPlaying) "Пауза" else "Играть",
                            22.dp,
                            actionRunCallback<TogglePlaybackAction>(),
                        )
                        if (!narrow) {
                            Spacer(modifier = GlanceModifier.size(4.dp))
                            TransportButton(R.drawable.ic_widget_next, "Следующий", 18.dp, actionRunCallback<SkipNextAction>())
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val MINI = DpSize(130.dp, 60.dp)
        val COMPACT = DpSize(200.dp, 60.dp)
        val WIDE = DpSize(280.dp, 60.dp)
        val TALL_SMALL = DpSize(150.dp, 120.dp)
        val TALL_LARGE = DpSize(260.dp, 130.dp)
    }
}

@androidx.compose.runtime.Composable
private fun Art(art: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp, modifier: GlanceModifier = GlanceModifier) {
    Box(modifier = GlanceModifier.size(size).then(widgetCorner(10)).background(WidgetSurface).then(modifier)) {
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
}

@androidx.compose.runtime.Composable
private fun TrackText(title: String?, artist: String?) {
    Text(
        title ?: "Ничего не играет",
        style = TextStyle(color = WidgetTextPrimary, fontSize = 13.sp),
        maxLines = 1,
    )
    artist?.let {
        Text(it, style = TextStyle(color = WidgetTextSecondary, fontSize = 11.sp), maxLines = 1)
    }
}

class NamiWidgetCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetCompact()
}
