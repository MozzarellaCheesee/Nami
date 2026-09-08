package dev.nami.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import dev.nami.core.designsystem.NamiAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.TRASH_RETENTION_MS
import dev.nami.domain.TrashedAlbum
import dev.nami.domain.TrashedPlaylist
import dev.nami.domain.TrashedTrack
import java.util.concurrent.TimeUnit

@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    var showClearAllConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                text = "Корзина",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (uiState.tracks.isNotEmpty() || uiState.playlists.isNotEmpty() || uiState.albums.isNotEmpty()) {
                TextButton(onClick = { showClearAllConfirm = true }) {
                    Text(text = "Очистить всё", color = NamiColors.Paper70)
                }
            }
        }

        if (uiState.tracks.isEmpty() && uiState.playlists.isEmpty() && uiState.albums.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Корзина пуста", color = NamiColors.Paper70)
            }
        } else {
            LazyColumn {
                if (uiState.albums.isNotEmpty()) {
                    item { SectionHeader("Альбомы") }
                    items(uiState.albums, key = { "album-${it.album.id.value}" }) { trashed ->
                        TrashedAlbumRow(
                            trashed = trashed,
                            onRestore = { viewModel.restoreAlbum(trashed.album.id) },
                            onDeleteForever = { viewModel.deleteAlbumForever(trashed.album.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (uiState.tracks.isNotEmpty()) {
                    item { SectionHeader("Треки") }
                    items(uiState.tracks, key = { "track-${it.track.id.value}" }) { trashed ->
                        TrashedTrackRow(
                            trashed = trashed,
                            onRestore = { viewModel.restoreTrack(trashed.track.id) },
                            onDeleteForever = { viewModel.deleteTrackForever(trashed.track.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (uiState.playlists.isNotEmpty()) {
                    item { SectionHeader("Плейлисты") }
                    items(uiState.playlists, key = { "playlist-${it.playlist.id.value}" }) { trashed ->
                        TrashedPlaylistRow(
                            trashed = trashed,
                            onRestore = { viewModel.restorePlaylist(trashed.playlist.id) },
                            onDeleteForever = { viewModel.deletePlaylistForever(trashed.playlist.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (showClearAllConfirm) {
        NamiAlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Очистить корзину?") },
            text = { Text("Все треки и плейлисты будут удалены навсегда. Это действие необратимо.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAllForever(); showClearAllConfirm = false }) {
                    Text("Очистить")
                }
            },
            dismissButton = { TextButton(onClick = { showClearAllConfirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = NamiColors.Paper70,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun daysRemaining(deletedAt: Long): Long {
    val elapsed = System.currentTimeMillis() - deletedAt
    val remainingMs = TRASH_RETENTION_MS - elapsed
    return TimeUnit.MILLISECONDS.toDays(remainingMs).coerceAtLeast(0)
}

@Composable
private fun TrashedTrackRow(trashed: TrashedTrack, onRestore: () -> Unit, onDeleteForever: () -> Unit, modifier: Modifier = Modifier) {
    var showConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = trashed.track.title, color = NamiColors.Paper100)
            Text(
                text = "Осталось дней: ${daysRemaining(trashed.deletedAt)}",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.Outlined.Restore, contentDescription = "Восстановить", tint = NamiColors.Paper70)
        }
        IconButton(onClick = { showConfirm = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Удалить навсегда", tint = NamiColors.Paper70)
        }
    }
    if (showConfirm) {
        DeleteForeverDialog(onConfirm = { onDeleteForever(); showConfirm = false }, onDismiss = { showConfirm = false })
    }
}

@Composable
private fun TrashedAlbumRow(trashed: TrashedAlbum, onRestore: () -> Unit, onDeleteForever: () -> Unit, modifier: Modifier = Modifier) {
    var showConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = trashed.album.title, color = NamiColors.Paper100)
            Text(
                text = "Осталось дней: ${daysRemaining(trashed.deletedAt)}",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.Outlined.Restore, contentDescription = "Восстановить", tint = NamiColors.Paper70)
        }
        IconButton(onClick = { showConfirm = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Удалить навсегда", tint = NamiColors.Paper70)
        }
    }
    if (showConfirm) {
        DeleteForeverDialog(onConfirm = { onDeleteForever(); showConfirm = false }, onDismiss = { showConfirm = false })
    }
}

@Composable
private fun TrashedPlaylistRow(trashed: TrashedPlaylist, onRestore: () -> Unit, onDeleteForever: () -> Unit, modifier: Modifier = Modifier) {
    var showConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = trashed.playlist.name, color = NamiColors.Paper100)
            Text(
                text = "Осталось дней: ${daysRemaining(trashed.deletedAt)}",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.Outlined.Restore, contentDescription = "Восстановить", tint = NamiColors.Paper70)
        }
        IconButton(onClick = { showConfirm = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Удалить навсегда", tint = NamiColors.Paper70)
        }
    }
    if (showConfirm) {
        DeleteForeverDialog(onConfirm = { onDeleteForever(); showConfirm = false }, onDismiss = { showConfirm = false })
    }
}

@Composable
private fun DeleteForeverDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить навсегда?") },
        text = { Text("Это действие необратимо.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
