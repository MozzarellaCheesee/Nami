package dev.nami.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.feature.playlists.AddToPlaylistDialog
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LibraryScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    onImportRequested: () -> Unit,
    onImportFolderRequested: () -> Unit,
    importProgress: StateFlow<ImportProgress?>,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val activeImportProgress by importProgress.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showAddSelectedToPlaylist by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectionMode = uiState.selectedTrackIds.isNotEmpty()

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearSelection() }
    }

    LaunchedEffect(uiState.lastDeletedTrackIds) {
        if (uiState.lastDeletedTrackIds.isEmpty()) return@LaunchedEffect
        val message = if (uiState.lastDeletedTrackIds.size == 1) "Трек удалён" else "Удалено треков: ${uiState.lastDeletedTrackIds.size}"
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Отменить",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoLastDelete()
        } else {
            viewModel.dismissDeleteSnackbar()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(NamiColors.Ink900)) {
            Column {
                if (selectionMode) {
                    SelectionTopBar(
                        selectedCount = uiState.selectedTrackIds.size,
                        onCancel = viewModel::clearSelection,
                        onDelete = viewModel::deleteSelectedTracks,
                        onAddToPlaylist = { showAddSelectedToPlaylist = true },
                    )
                } else {
                    LibraryChipsRow(selected = uiState.selectedTab, onSelect = viewModel::selectTab)
                }
                when (uiState.selectedTab) {
                    LibraryTab.TRACKS -> TrackListContent(
                        viewModel = viewModel,
                        selectionMode = selectionMode,
                        selectedTrackIds = uiState.selectedTrackIds,
                        onTrackClick = onTrackClick,
                        onAddToPlaylist = { trackId -> addToPlaylistTrackId = trackId },
                        onDelete = { trackId -> viewModel.deleteTrack(trackId) },
                        onToggleSelection = { trackId -> viewModel.toggleTrackSelection(trackId) },
                    )
                    LibraryTab.ALBUMS -> AlbumGridContent(viewModel, onAlbumClick)
                    LibraryTab.ARTISTS -> ArtistListContent(viewModel, onArtistClick)
                }
            }

            if (!selectionMode) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) {
                    activeImportProgress?.let { progress ->
                        ImportProgressBadge(progress = progress, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    FloatingActionButton(
                        onClick = onImportFolderRequested,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = "Импортировать папку")
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                    FloatingActionButton(onClick = onImportRequested) {
                        Icon(Icons.Filled.Add, contentDescription = "Импортировать файлы")
                    }
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }

    if (showAddSelectedToPlaylist) {
        AddToPlaylistDialog(
            trackIds = uiState.selectedTrackIds,
            onDismiss = {
                showAddSelectedToPlaylist = false
                viewModel.clearSelection()
            },
        )
    }
}

@Composable
private fun ImportProgressBadge(progress: ImportProgress, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(NamiColors.Ink800, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = "Импорт: ${progress.done}/${progress.total}",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Отменить выбор", tint = NamiColors.Paper100)
        }
        Text(
            text = "Выбрано: $selectedCount",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onAddToPlaylist) {
            Icon(Icons.Filled.LibraryAdd, contentDescription = "В плейлист", tint = NamiColors.Paper70)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = NamiColors.Paper70)
        }
    }
}

@Composable
private fun LibraryChipsRow(selected: LibraryTab, onSelect: (LibraryTab) -> Unit) {
    val labels = mapOf(LibraryTab.TRACKS to "Треки", LibraryTab.ALBUMS to "Альбомы", LibraryTab.ARTISTS to "Артисты")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(
                        if (isSelected) NamiColors.Paper100 else NamiColors.Ink800,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = { onSelect(tab) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labels.getValue(tab),
                    color = if (isSelected) NamiColors.Ink900 else NamiColors.Paper70,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TrackListContent(
    viewModel: LibraryViewModel,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    onTrackClick: (TrackId) -> Unit,
    onAddToPlaylist: (TrackId) -> Unit,
    onDelete: (TrackId) -> Unit,
    onToggleSelection: (TrackId) -> Unit,
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    if (tracks.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
            items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                tracks[index]?.let { track ->
                    TrackListItem(
                        track = track,
                        onClick = {
                            if (selectionMode) onToggleSelection(track.id) else onTrackClick(track.id)
                        },
                        onLongClick = { onToggleSelection(track.id) },
                        selectionMode = selectionMode,
                        isSelected = track.id in selectedTrackIds,
                        onAddToPlaylist = if (selectionMode) null else { { onAddToPlaylist(track.id) } },
                        onDelete = if (selectionMode) null else { { onDelete(track.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumGridContent(viewModel: LibraryViewModel, onAlbumClick: (AlbumId) -> Unit) {
    val albums = viewModel.albums.collectAsLazyPagingItems()
    if (albums.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 172.dp)) {
            items(count = albums.itemCount, key = albums.itemKey { it.id.value }) { index ->
                albums[index]?.let { album ->
                    AlbumGridItem(
                        album = album,
                        onClick = { onAlbumClick(album.id) },
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistListContent(viewModel: LibraryViewModel, onArtistClick: (ArtistId) -> Unit) {
    val artists = viewModel.artists.collectAsLazyPagingItems()
    if (artists.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyColumn {
            items(count = artists.itemCount, key = artists.itemKey { it.id.value }) { index ->
                artists[index]?.let { artist -> ArtistListItem(artist = artist, onClick = { onArtistClick(artist.id) }) }
            }
        }
    }
}

@Composable
private fun EmptyLibraryMessage() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = "Добавьте музыку с компьютера",
            color = NamiColors.Paper70,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
