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
import androidx.glance.appwidget.LinearProgressIndicator
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
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
            val isSmallWidth = size.width < 180.dp
            val showShuffle = size.width >= 240.dp

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .then(widgetCorner())
                    .background(WidgetBackground),
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
                        .padding(
                            horizontal = if (isSmallWidth) 10.dp else 14.dp,
                            vertical = if (isCompactHeight) 8.dp else 12.dp,
                        ),
                    verticalAlignment = Alignment.Vertical.Bottom,
                ) {
                    // Верхняя область обложки кликабельна для перехода в плеер
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .defaultWeight()
                            .clickable(openPlayerAction),
                    ) {}

                    // Строка метаданных: Название, артист и кнопка лайка
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Column(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .clickable(openPlayerAction),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            Text(
                                nowPlaying?.title ?: "Ничего не играет",
                                style = TextStyle(
                                    color = WidgetTextPrimary,
                                    fontSize = if (isSmallWidth || isCompactHeight) 14.sp else 16.sp,
                                ),
                                maxLines = 1,
                            )
                            val subtitle = nowPlaying?.artistName ?: if (nowPlaying == null) "Нажмите, чтобы открыть Nami" else null
                            subtitle?.let {
                                Text(
                                    it,
                                    style = TextStyle(color = WidgetTextSecondary, fontSize = 12.sp),
                                    maxLines = 1,
                                    modifier = GlanceModifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (nowPlaying != null) {
                            TransportButton(
                                if (isLiked) R.drawable.ic_widget_like_filled else R.drawable.ic_widget_like_outline,
                                if (isLiked) "Убрать из любимых" else "В любимые",
                                size = 20.dp,
                                action = actionRunCallback<ToggleLikeAction>(),
                                tint = if (isLiked) WidgetAccent else WidgetTextPrimary,
                                targetSize = if (isSmallWidth) 36.dp else 42.dp,
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.size(if (isCompactHeight) 6.dp else 8.dp))

                    // Полноширинный прогресс-бар
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                        color = WidgetAccent,
                        backgroundColor = WidgetTrackEmpty,
                    )

                    Spacer(modifier = GlanceModifier.size(if (isCompactHeight) 6.dp else 10.dp))

                    // Панель управления на всю ширину с центрированием кнопок
                    TransportRow(
                        isPlaying = isPlayingNow(repo),
                        iconSize = if (isSmallWidth) 20.dp else (if (isCompactHeight) 24.dp else 28.dp),
                        playTargetSize = if (isSmallWidth) 40.dp else 48.dp,
                        secondaryTargetSize = if (isSmallWidth) 36.dp else 42.dp,
                        spacerSize = if (isSmallWidth) 4.dp else 6.dp,
                        showShuffle = showShuffle,
                        isShuffle = isShuffle,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    private companion object {
        val TINY = DpSize(120.dp, 120.dp)
        val HORIZONTAL = DpSize(220.dp, 100.dp)
        val SMALL = DpSize(160.dp, 160.dp)
        val MEDIUM = DpSize(220.dp, 220.dp)
        val LARGE = DpSize(300.dp, 300.dp)
    }
}

class NamiWidgetSquarePlayerReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetSquarePlayer()
}
