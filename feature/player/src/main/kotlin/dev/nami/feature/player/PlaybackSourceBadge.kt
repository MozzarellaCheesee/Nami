package dev.nami.feature.player

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.TrackPlaybackSource

@Composable
fun PlaybackSourceIcon(
    source: TrackPlaybackSource,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    customTint: Color? = null,
) {
    val color = customTint ?: when (source) {
        TrackPlaybackSource.LOCAL -> NamiColors.Wakaba
        TrackPlaybackSource.SERVER -> NamiColors.Ai
        TrackPlaybackSource.UNAVAILABLE -> NamiColors.Paper40
    }
    val icon = when (source) {
        TrackPlaybackSource.LOCAL -> Icons.Outlined.Storage
        TrackPlaybackSource.SERVER -> Icons.Outlined.Cloud
        TrackPlaybackSource.UNAVAILABLE -> Icons.Outlined.CloudOff
    }
    Icon(icon, contentDescription = source.sourceDescription(), tint = color, modifier = modifier.size(size))
}

@Composable
fun PlaybackSourceBadge(
    source: TrackPlaybackSource,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val context = LocalContext.current
    val (bgColor, textColor, label) = when (source) {
        TrackPlaybackSource.LOCAL -> Triple(NamiColors.Wakaba.copy(alpha = 0.14f), NamiColors.Wakaba, "На устройстве")
        TrackPlaybackSource.SERVER -> Triple(NamiColors.Ai.copy(alpha = 0.14f), NamiColors.Ai, "Поток с сервера")
        TrackPlaybackSource.UNAVAILABLE -> Triple(NamiColors.Shu.copy(alpha = 0.14f), NamiColors.Paper70, "Недоступен")
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable { Toast.makeText(context, source.detailedToastText(), Toast.LENGTH_SHORT).show() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PlaybackSourceIcon(source, size = 13.dp, customTint = textColor)
            if (showLabel) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "playback-source-label",
                ) { text -> Text(text, color = textColor, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

private fun TrackPlaybackSource.sourceDescription(): String = when (this) {
    // Скачанный трек сюда же: файл на накопителе, сеть для него не нужна.
    TrackPlaybackSource.LOCAL -> "Файл на устройстве"
    TrackPlaybackSource.SERVER -> "Поток с сервера Nami"
    TrackPlaybackSource.UNAVAILABLE -> "Сервер недоступен"
}

private fun TrackPlaybackSource.detailedToastText(): String = "Источник: ${sourceDescription().lowercase()}"

/**
 * Есть ли трек на сервере Nami - вопрос, независимый от того, откуда он сейчас играет
 * ([PlaybackSourceBadge]): скачанный трек играет с устройства и при этом остаётся на сервере,
 * а локальный файл сервер может не знать вовсе.
 *
 * [onServer] `null` - сервер не подключён или ещё не ответил: плашка не рисуется, потому что
 * «нет на сервере» в этом случае было бы неправдой.
 */
@Composable
fun ServerPresenceBadge(
    onServer: Boolean?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    if (onServer == null) return
    val context = LocalContext.current
    val color = if (onServer) NamiColors.Ai else NamiColors.Paper40
    val label = if (onServer) "На сервере" else "Нет на сервере"
    val toast = if (onServer) {
        "Трек есть в библиотеке сервера Nami"
    } else {
        "Трека нет на сервере Nami - только на этом устройстве"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable { Toast.makeText(context, toast, Toast.LENGTH_SHORT).show() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(
                if (onServer) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(13.dp),
            )
            if (showLabel) {
                Text(label, color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
