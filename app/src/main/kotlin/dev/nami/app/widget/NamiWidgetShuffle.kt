package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.nami.app.R

/** Виджет 7/8 - одна кнопка "случайно": тап собирает случайную очередь по всей библиотеке и
 * сразу играет (RadioBuilder не подходит - тут нужен честный случайный шаффл без похожести на
 * seed, а не "радио от трека"). */
class NamiWidgetShuffle : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetSurface)
                    .clickable(actionRunCallback<ShufflePlayAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    Image(provider = ImageProvider(R.drawable.ic_widget_shuffle), contentDescription = null, colorFilter = ColorFilter.tint(WidgetTextPrimary), modifier = GlanceModifier.size(28.dp))
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.size(6.dp))
                    Text("случайно", style = TextStyle(color = WidgetTextSecondary, fontSize = 13.sp))
                }
            }
        }
    }
}

class NamiWidgetShuffleReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetShuffle()
}
