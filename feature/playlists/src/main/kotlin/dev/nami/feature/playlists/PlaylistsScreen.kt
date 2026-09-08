package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.QueueMusic
import dev.nami.core.designsystem.NamiAlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiScreenHeader
import dev.nami.core.designsystem.NamiType
import dev.nami.core.model.PlaylistId
import dev.nami.domain.ImportM3u8Result
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistId) -> Unit,
    onImportRequested: (playlistName: String) -> Unit,
    onCreateSmartPlaylist: () -> Unit,
    lastImportResult: StateFlow<ImportM3u8Result?>,
    onImportResultShown: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists = viewModel.playlists.collectAsLazyPagingItems()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCreateChooser by remember { mutableStateOf(false) }
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

    Scaffold(
        containerColor = NamiColors.Ink900,
        snackbarHost = { dev.nami.core.designsystem.NamiSnackbarHost(snackbarHostState) },
    ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).background(NamiColors.Ink900)) {
        // Импорт .m3u8 переехал из плавающей кнопки внизу слева (она лежала поверх сетки и
        // перекрывала последний ряд карточек) в действие шапки - рядом с заголовком, где ему и
        // место: это редкое действие, а не второй по важности жест экрана.
        NamiScreenHeader(
            title = "Плейлисты",
            subtitle = if (playlists.itemCount > 0) "${playlists.itemCount} шт." else null,
            actions = {
                androidx.compose.material3.IconButton(onClick = { showImportDialog = true }) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = "Импортировать .m3u8", tint = NamiColors.Paper70)
                }
            },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (playlists.itemCount == 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 80.dp),
                ) {
                    Icon(
                        Icons.Outlined.QueueMusic,
                        contentDescription = null,
                        tint = NamiColors.Ink500,
                        modifier = Modifier.padding(bottom = 16.dp).then(Modifier),
                    )
                    Text("Плейлистов пока нет", color = NamiColors.Paper70, style = NamiType.TrackTitle)
                    Text(
                        "Обычный собирается руками, умный - по правилам и обновляется сам",
                        color = NamiColors.Paper40,
                        style = NamiType.Secondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        NamiPill("Обычный", NamiColors.Shu) { showCreateDialog = true }
                        NamiPill("Умный", NamiColors.Ai, onClick = onCreateSmartPlaylist)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 172.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
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

            FloatingActionButton(
                onClick = { showCreateChooser = true },
                containerColor = NamiColors.Shu,
                contentColor = NamiColors.Paper100,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Новый плейлист")
            }
        }
    }
    }

    if (showCreateChooser) {
        dev.nami.core.designsystem.ContextActionSheet(
            onDismiss = { showCreateChooser = false },
            actions = listOf(
                dev.nami.core.designsystem.ContextAction("Обычный плейлист", Icons.Outlined.Add) { showCreateDialog = true },
                dev.nami.core.designsystem.ContextAction("Умный плейлист", Icons.Outlined.Add, onClick = onCreateSmartPlaylist),
            ),
        )
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
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, color = NamiColors.Paper100) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NamiColors.Paper100,
                    unfocusedTextColor = NamiColors.Paper100,
                    focusedBorderColor = NamiColors.Shu,
                    unfocusedBorderColor = NamiColors.Ink600,
                    cursorColor = NamiColors.Shu,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("OK", color = NamiColors.Shu) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = NamiColors.Paper70) }
        },
    )
}
