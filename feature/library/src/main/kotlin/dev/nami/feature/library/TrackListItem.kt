package dev.nami.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.Track

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onRemoveFromAlbum: (() -> Unit)? = null,
    onRemoveFromArtist: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    isCurrentTrack: Boolean = false,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = isCurrentTrack,
            enter = expandHorizontally(animationSpec = tween(200)) + fadeIn(tween(200)),
            exit = shrinkHorizontally(animationSpec = tween(200)) + fadeOut(tween(200)),
        ) {
            Row {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(32.dp)
                        .background(NamiColors.Shu, RoundedCornerShape(1.dp)),
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
        }
        if (track.albumArtworkPath != null) {
            AsyncImage(
                model = track.albumArtworkPath,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        }
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            val titleColor by animateColorAsState(
                targetValue = if (isCurrentTrack) NamiColors.Shu else NamiColors.Paper100,
                animationSpec = tween(200),
                label = "track-title-color",
            )
            Text(
                text = track.title,
                color = titleColor,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.animation.AnimatedContent(
                targetState = isCurrentTrack,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "track-subtitle",
            ) { showIndicator ->
                if (showIndicator) {
                    MiniPlayingIndicator(isPlaying = isPlaying, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(
                        text = subtitleFor(track),
                        color = NamiColors.Paper70,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = null)
        } else if (onAddToPlaylist != null || onAddToQueue != null || onDelete != null || onRename != null || onRemoveFromAlbum != null || onRemoveFromArtist != null) {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper40)
            }
            if (showMenu) {
                ContextActionSheet(
                    onDismiss = { showMenu = false },
                    actions = listOfNotNull(
                        onAddToPlaylist?.let { ContextAction("В плейлист", Icons.Outlined.LibraryAdd, it) },
                        onAddToQueue?.let { ContextAction("В очередь", Icons.Outlined.PlaylistAdd, it) },
                        onRename?.let { ContextAction("Переименовать", Icons.Outlined.Edit, it) },
                        onRemoveFromAlbum?.let { ContextAction("Убрать из альбома", Icons.Outlined.Delete, it) },
                        onRemoveFromArtist?.let { ContextAction("Убрать у артиста", Icons.Outlined.Delete, it) },
                        onDelete?.let { ContextAction("Удалить", Icons.Outlined.Delete, it) },
                    ),
                )
            }
        }
    }
}

private fun subtitleFor(track: Track): String {
    val duration = formatDuration(track.durationMs)
    val parts = listOfNotNull(
        track.artistName,
        duration,
        // Only shown once a track has actually been played -- a "0 прослушиваний" badge on
        // every never-played row would just be noise.
        if (track.playCount > 0) "${track.playCount} ${playsWord(track.playCount)}" else null,
    )
    return parts.joinToString(" · ")
}

private fun playsWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "прослушиваний"
        mod10 == 1 -> "прослушивание"
        mod10 in 2..4 -> "прослушивания"
        else -> "прослушиваний"
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
