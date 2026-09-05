package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.Track

@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                text = track.title,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = formatDuration(track.durationMs),
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = "В плейлист", tint = NamiColors.Paper70)
            }
        }
        if (onAddToQueue != null) {
            IconButton(onClick = onAddToQueue) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "В очередь", tint = NamiColors.Paper70)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = NamiColors.Paper70)
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
