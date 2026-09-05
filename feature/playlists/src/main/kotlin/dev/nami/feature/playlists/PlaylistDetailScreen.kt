package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.Track

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onExportRequested: (PlaylistId) -> Unit,
    onPickCoverRequested: (PlaylistId) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            IconButton(onClick = { onPickCoverRequested(viewModel.playlistId) }) {
                Icon(Icons.Filled.Image, contentDescription = "Обложка", tint = NamiColors.Paper70)
            }
            IconButton(onClick = { onExportRequested(viewModel.playlistId) }) {
                Icon(Icons.Filled.Share, contentDescription = "Экспорт в .m3u8", tint = NamiColors.Paper70)
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить плейлист", tint = NamiColors.Paper70)
            }
        }
        if (uiState.playlist?.coverPath != null) {
            AsyncImage(
                model = uiState.playlist?.coverPath,
                contentDescription = uiState.playlist?.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = uiState.playlist?.name ?: "",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).clickable { showRenameDialog = true },
            )
            if (uiState.tracks.isNotEmpty()) {
                Button(onClick = { onPlayTracks(uiState.tracks, 0) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(text = "Играть")
                }
            }
        }
        LazyColumn {
            itemsIndexed(uiState.tracks, key = { _, track -> track.id.value }) { index, track ->
                PlaylistTrackRow(
                    track = track,
                    onClick = { onPlayTracks(uiState.tracks, index) },
                    onRemove = { viewModel.removeTrack(track.id) },
                )
            }
        }
    }

    if (showRenameDialog) {
        var text by remember { mutableStateOf(uiState.playlist?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать плейлист") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) viewModel.rename(text)
                    showRenameDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Отмена") } },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить плейлист?") },
            text = { Text("Треки останутся в библиотеке. Плейлист будет в корзине 30 дней.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(onDeleted)
                    showDeleteDialog = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PlaylistTrackRow(track: Track, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = track.title, color = NamiColors.Paper100, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Убрать из плейлиста", tint = NamiColors.Paper70)
        }
    }
}
