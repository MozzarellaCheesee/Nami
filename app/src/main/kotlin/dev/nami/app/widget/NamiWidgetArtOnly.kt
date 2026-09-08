package dev.nami.app.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import dev.nami.app.R

/** Виджет 4/8 - только обложка, декоративный, тап открывает Now Playing. */
class NamiWidgetArtOnly : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val art = loadArtBitmap(widgetPlayerRepository(context).queue.value.nowPlaying?.artworkPath)

        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().then(widgetCorner()).background(WidgetSurface)
                    .clickable(actionStartActivity(openPlayerIntent(context))),
            ) {
                if (art != null) {
                    Image(provider = ImageProvider(art), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_launcher_wave),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize().padding(24.dp),
                    )
                }
            }
        }
    }
}

class NamiWidgetArtOnlyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NamiWidgetArtOnly()
}
