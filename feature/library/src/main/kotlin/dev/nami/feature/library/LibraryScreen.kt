package dev.nami.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.feature.playlists.AddToPlaylistDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

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
    val recentAlbums by viewModel.recentAlbums.collectAsState()
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
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

    val trackListState = rememberLazyListState()
    val albumGridState = rememberLazyGridState()
    val artistListState = rememberLazyListState()
    // FABs float over the list instead of reserving permanent empty space at the bottom --
    // hide them while scrolling down so they never sit over content being read, and bring
    // them back on scroll-up or when idle.
    val trackListScrollingDown = trackListState.isScrollingDown()
    val albumGridScrollingDown = albumGridState.isScrollingDown()
    val artistListScrollingDown = artistListState.isScrollingDown()
    val fabsVisible = !when (uiState.selectedTab) {
        LibraryTab.TRACKS -> trackListScrollingDown
        LibraryTab.ALBUMS -> albumGridScrollingDown
        LibraryTab.ARTISTS -> artistListScrollingDown
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(NamiColors.Ink900)) {
            Column {
                if (selectionMode) {
                    SelectionTopBar(
                        selectedCount = uiState.selectedTrackIds.size,
                        onCancel = viewModel::clearSelection,
                        onSelectAll = {
                            viewModel.setSelectedTracks(tracks.itemSnapshotList.items.mapNotNull { it?.id }.toSet())
                        },
                        onDelete = viewModel::deleteSelectedTracks,
                        onAddToPlaylist = { showAddSelectedToPlaylist = true },
                    )
                } else {
                    LibraryChipsRow(selected = uiState.selectedTab, onSelect = viewModel::selectTab)
                }
                when (uiState.selectedTab) {
                    LibraryTab.TRACKS -> TrackListContent(
                        tracks = tracks,
                        listState = trackListState,
                        selectionMode = selectionMode,
                        selectedTrackIds = uiState.selectedTrackIds,
                        recentAlbums = recentAlbums,
                        onTrackClick = onTrackClick,
                        onAlbumClick = onAlbumClick,
                        onShowAllAlbums = { viewModel.selectTab(LibraryTab.ALBUMS) },
                        onAddToPlaylist = { trackId -> addToPlaylistTrackId = trackId },
                        onDelete = { trackId -> viewModel.deleteTrack(trackId) },
                        onToggleSelection = { trackId -> viewModel.toggleTrackSelection(trackId) },
                        onSetSelection = { ids -> viewModel.setSelectedTracks(ids) },
                    )
                    LibraryTab.ALBUMS -> AlbumGridContent(viewModel, albumGridState, onAlbumClick)
                    LibraryTab.ARTISTS -> ArtistListContent(viewModel, artistListState, onArtistClick)
                }
            }

            if (!selectionMode) {
                AnimatedVisibility(
                    visible = fabsVisible,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(20.dp),
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
    onSelectAll: () -> Unit,
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
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Filled.Done, contentDescription = "Выбрать все", tint = NamiColors.Paper70)
        }
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

/** True once the user has scrolled down past the top and the last delta was downward. */
@Composable
private fun LazyListState.isScrollingDown(): Boolean {
    var previousIndex by remember(this) { mutableStateOf(firstVisibleItemIndex) }
    var previousOffset by remember(this) { mutableStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val down = if (previousIndex != firstVisibleItemIndex) {
                previousIndex < firstVisibleItemIndex
            } else {
                previousOffset < firstVisibleItemScrollOffset
            }
            previousIndex = firstVisibleItemIndex
            previousOffset = firstVisibleItemScrollOffset
            down && firstVisibleItemIndex > 0 || (down && firstVisibleItemScrollOffset > 0)
        }
    }.value
}

@Composable
private fun LazyGridState.isScrollingDown(): Boolean {
    var previousIndex by remember(this) { mutableStateOf(firstVisibleItemIndex) }
    var previousOffset by remember(this) { mutableStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val down = if (previousIndex != firstVisibleItemIndex) {
                previousIndex < firstVisibleItemIndex
            } else {
                previousOffset < firstVisibleItemScrollOffset
            }
            previousIndex = firstVisibleItemIndex
            previousOffset = firstVisibleItemScrollOffset
            down && (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0)
        }
    }.value
}

private const val DISCOGRAPHY_HEADER_KEY = "discography-header"
private const val AUTO_SCROLL_EDGE_DP = 64
private const val AUTO_SCROLL_MAX_PX_PER_TICK = 20f

@Composable
private fun TrackListContent(
    tracks: LazyPagingItems<Track>,
    listState: LazyListState,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    recentAlbums: List<AlbumSummary>,
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onShowAllAlbums: () -> Unit,
    onAddToPlaylist: (TrackId) -> Unit,
    onDelete: (TrackId) -> Unit,
    onToggleSelection: (TrackId) -> Unit,
    onSetSelection: (Set<TrackId>) -> Unit,
) {
    if (tracks.itemCount == 0) {
        EmptyLibraryMessage()
        return
    }

    // Drag-to-select, driven entirely from this parent Box (not from TrackListItem's own
    // long-press): a long-press with no further movement selects just that row; keeping the
    // finger down and dragging over other rows extends the selection to the range between the
    // anchor row and the finger's row. This has to be the ONLY long-press detector in the
    // touched region -- if TrackListItem also registered its own onLongClick, both detectors
    // would race for the same long-press on the same pointer stream and this one would starve
    // (Compose delivers pointer events to the child first, which consumes them detecting its
    // own long-press, leaving nothing for the parent to see). Auto-scrolls near the edges.
    var dragAnchorId by remember { mutableStateOf<TrackId?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var boxHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val onSetSelectionState = rememberUpdatedState(onSetSelection)

    fun trackIdAt(y: Float): TrackId? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?.key
            ?.let { it as? String }
            ?.takeIf { it != DISCOGRAPHY_HEADER_KEY }
            ?.let(::TrackId)

    fun updateDragSelection() {
        val anchor = dragAnchorId ?: return
        val orderedIds = tracks.itemSnapshotList.items.map { it?.id }
        val anchorIndex = orderedIds.indexOf(anchor)
        if (anchorIndex == -1) return
        val pointerIndex = trackIdAt(dragPointerY)?.let(orderedIds::indexOf)
            ?: if (dragPointerY < boxHeightPx / 2) 0 else orderedIds.lastIndex
        val range = minOf(anchorIndex, pointerIndex)..maxOf(anchorIndex, pointerIndex)
        onSetSelectionState.value(range.mapNotNull { orderedIds.getOrNull(it) }.toSet())
    }

    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edgePx = with(density) { AUTO_SCROLL_EDGE_DP.dp.toPx() }
        while (isActive) {
            val fromTop = dragPointerY
            val fromBottom = boxHeightPx - dragPointerY
            val scrollAmount = when {
                fromTop < edgePx -> -((edgePx - fromTop).coerceAtLeast(0f) / edgePx) * AUTO_SCROLL_MAX_PX_PER_TICK
                fromBottom < edgePx -> ((edgePx - fromBottom).coerceAtLeast(0f) / edgePx) * AUTO_SCROLL_MAX_PX_PER_TICK
                else -> 0f
            }
            if (scrollAmount != 0f) {
                listState.scrollBy(scrollAmount)
                updateDragSelection()
            }
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxHeightPx = it.height.toFloat() }
            .pointerInput(tracks.itemCount) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        trackIdAt(offset.y)?.let { id ->
                            dragAnchorId = id
                            dragging = true
                            onSetSelectionState.value(setOf(id))
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragAnchorId != null) {
                            change.consume()
                            dragPointerY = change.position.y
                            updateDragSelection()
                        }
                    },
                    onDragEnd = { dragging = false; dragAnchorId = null },
                    onDragCancel = { dragging = false; dragAnchorId = null },
                )
            },
    ) {
        LazyColumn(state = listState) {
            if (recentAlbums.isNotEmpty()) {
                item(key = DISCOGRAPHY_HEADER_KEY) {
                    DiscographySection(albums = recentAlbums, onAlbumClick = onAlbumClick, onShowAllAlbums = onShowAllAlbums)
                }
            }
            items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                tracks[index]?.let { track ->
                    TrackListItem(
                        track = track,
                        onClick = {
                            if (selectionMode) onToggleSelection(track.id) else onTrackClick(track.id)
                        },
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
private fun DiscographySection(
    albums: List<AlbumSummary>,
    onAlbumClick: (AlbumId) -> Unit,
    onShowAllAlbums: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Альбомы", color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Всё →",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onShowAllAlbums),
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.id.value }) { album ->
                AlbumGridItem(album = album, onClick = { onAlbumClick(album.id) }, modifier = Modifier.width(156.dp))
            }
        }
    }
}

@Composable
private fun AlbumGridContent(viewModel: LibraryViewModel, gridState: LazyGridState, onAlbumClick: (AlbumId) -> Unit) {
    val albums = viewModel.albums.collectAsLazyPagingItems()
    if (albums.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 172.dp), state = gridState) {
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
private fun ArtistListContent(viewModel: LibraryViewModel, listState: LazyListState, onArtistClick: (ArtistId) -> Unit) {
    val artists = viewModel.artists.collectAsLazyPagingItems()
    if (artists.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyColumn(state = listState) {
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
