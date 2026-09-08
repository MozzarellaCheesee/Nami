package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/** Виджет 6/8 - карточка с названием и исполнителем играющего трека, крупным шрифтом (макет
 * показывал оригинал+перевод названия - честно сузил до title+artist, перевод названия трека
 * нигде в приложении не хранится отдельно от текста песни, тянуть его сюда отдельная задача). */
class NamiWidgetNowPlayingText : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val nowPlaying = widgetPlayerRepository(context).queue.value.nowPlaying

        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetSurface)
                    .clickable(actionStartActivity(openPlayerIntent(context)))
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        nowPlaying?.title ?: "Ничего не играет",
                        style = TextStyle(color = WidgetTextPrimary, fontSize = 17.sp),
                        maxLines = 2,
                    )
                    nowPlaying?.artistName?.let {
                        Text(it, style = TextStyle(color = WidgetTextSecondary, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

class NamiWidgetNowPlayingTextReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetNowPlayingText()
}
