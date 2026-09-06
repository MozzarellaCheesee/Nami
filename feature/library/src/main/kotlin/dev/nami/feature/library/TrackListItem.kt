package dev.nami.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
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
            Text(
                text = track.title,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(track),
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = null)
        } else if (onAddToPlaylist != null || onAddToQueue != null || onDelete != null || onRename != null || onRemoveFromAlbum != null) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper40)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    onAddToPlaylist?.let { action ->
                        DropdownMenuItem(
                            text = { Text("В плейлист") },
                            leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onAddToQueue?.let { action ->
                        DropdownMenuItem(
                            text = { Text("В очередь") },
                            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onRename?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onRemoveFromAlbum?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Убрать из альбома") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onDelete?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                }
            }
        }
    }
}

private fun subtitleFor(track: Track): String {
    val duration = formatDuration(track.durationMs)
    return if (track.artistName != null) "${track.artistName} · $duration" else duration
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
