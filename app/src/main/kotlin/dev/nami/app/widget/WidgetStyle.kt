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
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nami.app.R
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
 * multi-megapixel cover into memory for it. Null on any decode failure (missing/corrupt file).
 *
 * QueueTrack.artworkPath comes from MediaMetadata.artworkUri.toString() (see
 * PlayerRepositoryImpl.toMediaItemInfo) - a "file:///data/..." URI STRING, not a bare filesystem
 * path. BitmapFactory.decodeFile() only understands bare paths and silently returns null for a
 * URI string with a scheme - this was the actual "обложка не выводилась" bug, not a missing
 * file. Uri.parse(path).path strips the scheme back to the real path decodeFile needs. */
fun loadArtBitmap(path: String?, targetPx: Int = 256): Bitmap? {
    if (path == null) return null
    val filePath = if (path.contains("://")) android.net.Uri.parse(path).path else path
    if (filePath == null) return null
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > targetPx * 2) sample *= 2
        BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}

class TogglePlaybackAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching {
                val repo = widgetPlayerRepository(context)
                repo.awaitReady()
                repo.toggle()
            }
        }
        delay(120)
        updateAllNamiWidgets(context)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching {
                val repo = widgetPlayerRepository(context)
                repo.awaitReady()
                repo.skipNext()
            }
        }
        delay(120)
        updateAllNamiWidgets(context)
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching {
                val repo = widgetPlayerRepository(context)
                repo.awaitReady()
                repo.skipToPreviousTrack()
            }
        }
        delay(120)
        updateAllNamiWidgets(context)
    }
}

class ToggleLikeAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching {
                val playerRepo = widgetPlayerRepository(context)
                playerRepo.awaitReady()
                val trackId = playerRepo.queue.value.nowPlaying?.id ?: return@runCatching
                widgetPlaylistRepository(context).toggleLike(trackId)
            }
        }
        delay(60)
        updateAllNamiWidgets(context)
    }
}

class ToggleShuffleAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching {
                val playerRepo = widgetPlayerRepository(context)
                playerRepo.awaitReady()
                val current = playerRepo.shuffleEnabled.value
                playerRepo.setShuffleEnabled(!current)
            }
        }
        delay(100)
        updateAllNamiWidgets(context)
    }
}

/** Refreshes every widget provider this app has - a button on ANY widget can change now-playing
 * state that every OTHER widget also shows, not just the one it lives on. */
suspend fun updateAllNamiWidgets(context: android.content.Context) {
    runCatching { NamiWidgetCompact().updateAll(context) }
    runCatching { NamiWidgetSquarePlayer().updateAll(context) }
    runCatching { NamiWidgetSessions().updateAll(context) }
}

@androidx.compose.runtime.Composable
fun TransportButton(
    iconRes: Int,
    contentDescription: String,
    size: Dp,
    action: Action,
    tint: ColorProvider = WidgetTextPrimary,
    targetSize: Dp = 44.dp,
) {
    Box(
        modifier = GlanceModifier
            .size(targetSize)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(size),
        )
    }
}

@androidx.compose.runtime.Composable
fun TransportRow(
    isPlaying: Boolean,
    iconSize: Dp = 24.dp,
    playTargetSize: Dp = 46.dp,
    secondaryTargetSize: Dp = 40.dp,
    spacerSize: Dp = 6.dp,
    showShuffle: Boolean = false,
    isShuffle: Boolean = false,
    showLike: Boolean = false,
    isLiked: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        if (showShuffle) {
            TransportButton(
                R.drawable.ic_widget_shuffle,
                "Случайно",
                (iconSize.value * 0.85f).dp.coerceAtLeast(16.dp),
                actionRunCallback<ToggleShuffleAction>(),
                tint = if (isShuffle) WidgetAccent else WidgetTextSecondary,
                targetSize = secondaryTargetSize,
            )
            androidx.glance.layout.Spacer(modifier = GlanceModifier.size((spacerSize.value * 0.75f).dp))
        }
        TransportButton(
            R.drawable.ic_widget_prev,
            "Предыдущий",
            iconSize,
            actionRunCallback<SkipPreviousAction>(),
            targetSize = secondaryTargetSize,
        )
        androidx.glance.layout.Spacer(modifier = GlanceModifier.size(spacerSize))
        TransportButton(
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            if (isPlaying) "Пауза" else "Играть",
            (iconSize.value * 1.25f).dp,
            actionRunCallback<TogglePlaybackAction>(),
            tint = WidgetAccent,
            targetSize = playTargetSize,
        )
        androidx.glance.layout.Spacer(modifier = GlanceModifier.size(spacerSize))
        TransportButton(
            R.drawable.ic_widget_next,
            "Следующий",
            iconSize,
            actionRunCallback<SkipNextAction>(),
            targetSize = secondaryTargetSize,
        )
        if (showLike) {
            androidx.glance.layout.Spacer(modifier = GlanceModifier.size((spacerSize.value * 0.75f).dp))
            TransportButton(
                if (isLiked) R.drawable.ic_widget_like_filled else R.drawable.ic_widget_like_outline,
                if (isLiked) "Убрать из любимых" else "В любимые",
                (iconSize.value * 0.85f).dp.coerceAtLeast(16.dp),
                actionRunCallback<ToggleLikeAction>(),
                tint = if (isLiked) WidgetAccent else WidgetTextSecondary,
                targetSize = secondaryTargetSize,
            )
        }
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

fun openSessionsIntent(context: android.content.Context): android.content.Intent =
    android.content.Intent().apply {
        setClassName(context.packageName, "dev.nami.app.MainActivity")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
