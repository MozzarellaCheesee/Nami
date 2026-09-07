package dev.nami.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.Track
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onLikeTrack: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onEditNote: (() -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
    onRemoveFromAlbum: (() -> Unit)? = null,
    onRemoveFromArtist: (() -> Unit)? = null,
    onShowInfo: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    isCurrentTrack: Boolean = false,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    // Swipe-to-queue: only when the caller actually gave us an add-to-queue action -- every call
    // site already passes null for it when nothing is playing (there's no "current track" to
    // queue after), so that's the same condition this reuses to decide whether the gesture exists
    // at all, not a separate flag to keep in sync.
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val revealThresholdPx = with(density) { 72.dp.toPx() }
    val maxDragPx = with(density) { 96.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth().height(64.dp)) {
        if (onAddToQueue != null) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.PlaylistAdd,
                    contentDescription = "В очередь следующим",
                    tint = if (dragOffsetX > revealThresholdPx) NamiColors.Shu else NamiColors.Paper40,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                .background(NamiColors.Ink900)
                .let { rowModifier ->
                    if (onAddToQueue == null) {
                        rowModifier
                    } else {
                        rowModifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta -> dragOffsetX = (dragOffsetX + delta).coerceIn(0f, maxDragPx) },
                            onDragStopped = {
                                if (dragOffsetX > revealThresholdPx) onAddToQueue()
                                scope.launch { animate(dragOffsetX, 0f) { value, _ -> dragOffsetX = value } }
                            },
                        )
                    }
                }
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
        } else if (onAddToPlaylist != null || onAddToQueue != null || onDelete != null || onRename != null || onRemoveFromAlbum != null || onRemoveFromArtist != null || onLikeTrack != null || onEditNote != null || onEditTags != null || onShowInfo != null) {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper40)
            }
            if (showMenu) {
                ContextActionSheet(
                    onDismiss = { showMenu = false },
                    actions = listOfNotNull(
                        onLikeTrack?.let { ContextAction("В любимые", Icons.Outlined.FavoriteBorder, it) },
                        onAddToPlaylist?.let { ContextAction("В плейлист", Icons.Outlined.LibraryAdd, it) },
                        onAddToQueue?.let { ContextAction("В очередь", Icons.Outlined.PlaylistAdd, it) },
                        onRename?.let { ContextAction("Переименовать", Icons.Outlined.Edit, it) },
                        onEditNote?.let { ContextAction("Заметка", Icons.Outlined.Notes, it) },
                        onEditTags?.let { ContextAction("Редактировать теги", Icons.Outlined.Label, it) },
                        onShowInfo?.let { ContextAction("Информация о треке", Icons.Outlined.Info, it) },
                        onRemoveFromAlbum?.let { ContextAction("Убрать из альбома", Icons.Outlined.Delete, it) },
                        onRemoveFromArtist?.let { ContextAction("Убрать у артиста", Icons.Outlined.Delete, it) },
                        onDelete?.let { ContextAction("Удалить", Icons.Outlined.Delete, it) },
                    ),
                )
            }
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

// formatDuration now lives in TrackInfoScreen.kt (internal, shared across this package).
