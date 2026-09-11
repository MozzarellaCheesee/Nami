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
import androidx.glance.layout.fillMaxWidth
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
            val tall = size.height >= 85.dp
            val narrow = size.width < 180.dp
            val wide = size.width >= 260.dp
            val isCompactHeight = size.height < 60.dp
            val artSize = if (tall) {
                if (size.height >= 140.dp) 80.dp else 56.dp
            } else {
                if (isCompactHeight) 36.dp else (if (wide) 46.dp else 40.dp)
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetBackground)
                    .then(widgetCorner())
                    .padding(
                        horizontal = if (narrow) 8.dp else 12.dp,
                        vertical = if (tall) 8.dp else (if (isCompactHeight) 4.dp else 6.dp),
                    )
            ) {
                if (tall) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Art(art, artSize, modifier = GlanceModifier.clickable(openPlayerAction))
                        Spacer(modifier = GlanceModifier.size(4.dp))
                        Column(
                            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                            modifier = GlanceModifier.clickable(openPlayerAction),
                        ) {
                            TrackText(nowPlaying?.title, nowPlaying?.artistName)
                        }
                        Spacer(modifier = GlanceModifier.size(6.dp))
                        TransportRow(
                            isPlaying = isPlaying,
                            iconSize = if (size.height < 120.dp) 18.dp else 22.dp,
                            playTargetSize = if (size.height < 120.dp) 38.dp else 44.dp,
                            secondaryTargetSize = if (size.height < 120.dp) 34.dp else 40.dp,
                            spacerSize = 4.dp,
                            showLike = wide || size.height >= 140.dp,
                            isLiked = isLiked,
                            modifier = GlanceModifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Art(art, artSize, modifier = GlanceModifier.clickable(openPlayerAction))
                        Spacer(modifier = GlanceModifier.size(if (narrow) 6.dp else 10.dp))
                        Column(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .clickable(openPlayerAction),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            TrackText(nowPlaying?.title, nowPlaying?.artistName)
                        }
                        Spacer(modifier = GlanceModifier.size(4.dp))
                        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                            if (wide && nowPlaying != null) {
                                TransportButton(
                                    if (isLiked) R.drawable.ic_widget_like_filled else R.drawable.ic_widget_like_outline,
                                    if (isLiked) "Убрать из любимых" else "В любимые",
                                    if (isCompactHeight) 16.dp else 18.dp,
                                    actionRunCallback<ToggleLikeAction>(),
                                    tint = if (isLiked) WidgetAccent else WidgetTextPrimary,
                                    targetSize = if (isCompactHeight) 34.dp else 38.dp,
                                )
                                Spacer(modifier = GlanceModifier.size(2.dp))
                            }
                            if (!narrow) {
                                TransportButton(
                                    R.drawable.ic_widget_prev,
                                    "Предыдущий",
                                    if (isCompactHeight) 16.dp else 18.dp,
                                    actionRunCallback<SkipPreviousAction>(),
                                    targetSize = if (isCompactHeight) 34.dp else 38.dp,
                                )
                                Spacer(modifier = GlanceModifier.size(2.dp))
                            }
                            TransportButton(
                                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                                if (isPlaying) "Пауза" else "Играть",
                                if (isCompactHeight) 20.dp else 24.dp,
                                actionRunCallback<TogglePlaybackAction>(),
                                tint = WidgetAccent,
                                targetSize = if (isCompactHeight) 38.dp else 44.dp,
                            )
                            if (!narrow) {
                                Spacer(modifier = GlanceModifier.size(2.dp))
                                TransportButton(
                                    R.drawable.ic_widget_next,
                                    "Следующий",
                                    if (isCompactHeight) 16.dp else 18.dp,
                                    actionRunCallback<SkipNextAction>(),
                                    targetSize = if (isCompactHeight) 34.dp else 38.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val MINI = DpSize(120.dp, 40.dp)
        val COMPACT = DpSize(180.dp, 40.dp)
        val WIDE = DpSize(260.dp, 40.dp)
        val TALL_SMALL = DpSize(140.dp, 90.dp)
        val TALL_LARGE = DpSize(240.dp, 90.dp)
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
