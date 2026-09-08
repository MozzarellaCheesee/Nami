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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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

/** Компакт - маленький квадрат: обложка + название/исполнитель + prev/play/next.
 *
 * SizeMode.Responsive, а не дефолтный Single: с Single Glance рендерит одну вёрстку под
 * минимальный размер из widget_info_compact.xml и при растягивании просто её растягивает -
 * кнопки уезжали за край на узком размере и болтались в пустоте на широком. Три точки
 * (узкая / обычная / высокая) - минимум, который честно различает реальные раскладки. */
class NamiWidgetCompact : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, TALL))

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
            val size = LocalSize.current
            val tall = size.height >= TALL.height
            val narrow = size.width < WIDE.width
            // На высоком размере появившееся место уходит в обложку (она же самая полезная
            // часть), поэтому там колонка, а не ряд.
            val artSize = if (tall) 64.dp else 40.dp
            Box(modifier = GlanceModifier.fillMaxSize().background(WidgetBackground).then(widgetCorner()).padding(10.dp)) {
                if (tall) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Art(art, artSize)
                        Spacer(modifier = GlanceModifier.size(6.dp))
                        TrackText(nowPlaying?.title, nowPlaying?.artistName)
                        Spacer(modifier = GlanceModifier.size(6.dp))
                        TransportRow(isPlaying, iconSize = 22.dp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Art(art, artSize)
                        Spacer(modifier = GlanceModifier.size(8.dp))
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            TrackText(nowPlaying?.title, nowPlaying?.artistName)
                        }
                        Spacer(modifier = GlanceModifier.size(6.dp))
                        // На узком размере prev/next не влезали вместе с текстом и обрезались -
                        // оставляем только play/pause, самую нужную кнопку.
                        if (!narrow) {
                            TransportButton(R.drawable.ic_widget_prev, "Предыдущий", 18.dp, actionRunCallback<SkipPreviousAction>())
                            Spacer(modifier = GlanceModifier.size(4.dp))
                        }
                        TransportButton(
                            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                            if (isPlaying) "Пауза" else "Играть",
                            18.dp,
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
        val NARROW = DpSize(140.dp, 64.dp)
        val WIDE = DpSize(220.dp, 64.dp)
        val TALL = DpSize(220.dp, 130.dp)
    }
}

@androidx.compose.runtime.Composable
private fun Art(art: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp) {
    Box(modifier = GlanceModifier.size(size).then(widgetCorner(10)).background(WidgetSurface)) {
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
