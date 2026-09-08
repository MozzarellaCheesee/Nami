package dev.nami.app.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nami.app.R
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository

/** Группа E "виджеты" (макет 4.15, 8 конфигураций) - общие цвета/помощники, каждый отдельный
 * виджет ниже - свой GlanceAppWidget + Receiver, т.к. Android-виджет-пикер показывает несколько
 * вариантов на приложение как отдельные записи, а не один изменяемый по размеру виджет. */
val WidgetBackground = ColorProvider(Color(0xFF0C0D0F))
val WidgetSurface = ColorProvider(Color(0xFF1A1B1F))
val WidgetTextPrimary = ColorProvider(Color(0xFFEDEAE4))
val WidgetTextSecondary = ColorProvider(Color(0xFF9B9A97))
val WidgetAccent = ColorProvider(Color(0xFFC24A34))
val WidgetTrackEmpty = ColorProvider(Color(0xFF3A3C43))

const val WIDGET_CORNER_RADIUS_DP = 20

fun widgetCorner(dp: Int = WIDGET_CORNER_RADIUS_DP) = GlanceModifier.cornerRadius(dp.dp)

/** Downsampled to widget-icon size - a widget shows this small, no reason to decode a
 * multi-megapixel cover into memory for it. Null on any decode failure (missing/corrupt file). */
fun loadArtBitmap(path: String?, targetPx: Int = 256): Bitmap? {
    if (path == null) return null
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (opts.outWidth / sample > targetPx * 2) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}

class TogglePlaybackAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        widgetPlayerRepository(context).toggle()
        updateAllNamiWidgets(context)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        widgetPlayerRepository(context).skipNext()
        updateAllNamiWidgets(context)
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        widgetPlayerRepository(context).skipToPreviousTrack()
        updateAllNamiWidgets(context)
    }
}

class ShufflePlayAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val library = widgetLibraryRepository(context)
        val tracks = library.allTracksOrdered()
        if (tracks.isEmpty()) return
        val seed = tracks.random()
        widgetPlayerRepository(context).play(
            listOf(
                dev.nami.domain.PlayableTrack(
                    id = seed.id,
                    title = seed.title,
                    artistName = seed.artistName,
                    path = seed.path,
                    artworkPath = seed.albumArtworkPath,
                    format = seed.format,
                ),
            ) + tracks.filter { it.id != seed.id }.shuffled().take(30).map {
                dev.nami.domain.PlayableTrack(id = it.id, title = it.title, artistName = it.artistName, path = it.path, artworkPath = it.albumArtworkPath, format = it.format)
            },
            startIndex = 0,
        )
        updateAllNamiWidgets(context)
    }
}

/** Refreshes every widget provider this app has - a button on ANY widget can change now-playing
 * state that every OTHER widget also shows, not just the one it lives on. */
suspend fun updateAllNamiWidgets(context: android.content.Context) {
    NamiWidgetCompact().updateAll(context)
    NamiWidgetPill().updateAll(context)
    NamiWidgetSquarePlayer().updateAll(context)
    NamiWidgetArtOnly().updateAll(context)
    NamiWidgetQueue().updateAll(context)
    NamiWidgetNowPlayingText().updateAll(context)
    NamiWidgetSessions().updateAll(context)
}

@androidx.compose.runtime.Composable
fun TransportButton(iconRes: Int, contentDescription: String, size: Dp, action: Action) {
    Image(
        provider = ImageProvider(iconRes),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(WidgetTextPrimary),
        modifier = GlanceModifier.size(size).clickable(action),
    )
}

@androidx.compose.runtime.Composable
fun TransportRow(isPlaying: Boolean, iconSize: Dp = 28.dp) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        TransportButton(R.drawable.ic_widget_prev, "Предыдущий", iconSize, actionRunCallback<SkipPreviousAction>())
        androidx.glance.layout.Spacer(modifier = GlanceModifier.size(12.dp))
        TransportButton(
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            if (isPlaying) "Пауза" else "Играть",
            iconSize,
            actionRunCallback<TogglePlaybackAction>(),
        )
        androidx.glance.layout.Spacer(modifier = GlanceModifier.size(12.dp))
        TransportButton(R.drawable.ic_widget_next, "Следующий", iconSize, actionRunCallback<SkipNextAction>())
    }
}

fun isPlayingNow(repo: PlayerRepository): Boolean = (repo.state.value as? PlaybackState.Playing)?.isPlaying == true

/** Открывает MainActivity сразу на Now Playing - тот же EXTRA_OPEN_PLAYER что системное
 * уведомление плеера использует (см. PlaybackService), не отдельный путь для виджетов. */
fun openPlayerIntent(context: android.content.Context): android.content.Intent =
    android.content.Intent().apply {
        setClassName(context.packageName, "dev.nami.app.MainActivity")
        putExtra(dev.nami.player.EXTRA_OPEN_PLAYER, true)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
