package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.PlaylistId
import dev.nami.domain.ImportM3u8Result
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistId) -> Unit,
    onImportRequested: (playlistName: String) -> Unit,
    lastImportResult: StateFlow<ImportM3u8Result?>,
    onImportResultShown: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists = viewModel.playlists.collectAsLazyPagingItems()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val importResult by lastImportResult.collectAsState()

    LaunchedEffect(importResult) {
        val result = importResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            "Импортировано: ${result.matchedCount}, пропущено: ${result.skippedCount}",
        )
        onImportResultShown()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding).background(NamiColors.Ink900)) {
        if (playlists.itemCount == 0) {
            Column(modifier = Modifier.align(Alignment.Center)) {
                Text(text = "Создайте первый плейлист", color = NamiColors.Paper70)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 172.dp)) {
                items(count = playlists.itemCount, key = playlists.itemKey { it.id.value }) { index ->
                    playlists[index]?.let { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.id) },
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = { showImportDialog = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        ) {
            Text(text = "Импортировать .m3u8", color = NamiColors.Paper70)
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Новый плейлист")
        }
    }
    }

    if (showCreateDialog) {
        NamePromptDialog(
            title = "Новый плейлист",
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    if (showImportDialog) {
        NamePromptDialog(
            title = "Имя плейлиста",
            onConfirm = { name ->
                onImportRequested(name)
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false },
        )
    }
}

@Composable
private fun NamePromptDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
