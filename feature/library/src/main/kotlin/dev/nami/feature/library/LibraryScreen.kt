package dev.nami.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.RenameDialog
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.feature.playlists.AddToPlaylistDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    onImportRequested: () -> Unit,
    onImportFolderRequested: () -> Unit,
    onImportZipRequested: () -> Unit,
    onShowTrackInfo: (TrackId) -> Unit,
    onStartRadio: (TrackId) -> Unit,
    onShareCard: (Track) -> Unit,
    importProgress: StateFlow<ImportProgress?>,
    // Bumped by the bottom nav's Library tab so re-tapping it while already here (or from any
    // other tab/detail screen) doesn't just switch back to the Tracks tab - it scrolls that
    // list back to the top too, actually landing on "the main screen with tracks", not wherever
    // it was left scrolled to.
    resetSignal: Int = 0,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val activeImportProgress by importProgress.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val recentAlbums by viewModel.recentAlbums.collectAsState()
    val featuredArtists by viewModel.featuredArtists.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showAddSelectedToPlaylist by remember { mutableStateOf(false) }
    var showBatchEditDialog by remember { mutableStateOf(false) }
    var editTagsTrackId by remember { mutableStateOf<TrackId?>(null) }
    var renameTrack by remember { mutableStateOf<Track?>(null) }
    var noteTrack by remember { mutableStateOf<Track?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectionMode = uiState.selectedTrackIds.isNotEmpty()
    val albumSelectionMode = uiState.selectedAlbumIds.isNotEmpty()

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = albumSelectionMode) { viewModel.clearAlbumSelection() }

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

    val serverActionMsg by viewModel.serverActionMsg.collectAsState()
    LaunchedEffect(serverActionMsg) {
        val msg = serverActionMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearServerActionMsg()
    }

    val trackListState = rememberLazyListState()
    val albumGridState = rememberLazyGridState()
    val artistListState = rememberLazyListState()
    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) trackListState.scrollToItem(0)
    }
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
        else -> false
    }
    BackHandler(enabled = uiState.openedGroup != null) { viewModel.closeBrowseGroup() }

    Scaffold(snackbarHost = { dev.nami.core.designsystem.NamiSnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(NamiColors.Ink900)) {
            Column {
                if (selectionMode) {
                    SelectionTopBar(
                        selectedCount = uiState.selectedTrackIds.size,
                        onCancel = viewModel::clearSelection,
                        onSelectAll = viewModel::selectAllTracks,
                        onDelete = viewModel::deleteSelectedTracks,
                        onAddToPlaylist = { showAddSelectedToPlaylist = true },
                        onLikeSelected = viewModel::likeSelectedTracks,
                        onEditTags = { showBatchEditDialog = true },
                        onUploadToServer = if (viewModel.isServerActive()) {
                            viewModel::uploadSelectedTracksToServer
                        } else null,
                    )
                } else if (albumSelectionMode) {
                    AlbumSelectionTopBar(
                        selectedCount = uiState.selectedAlbumIds.size,
                        onCancel = viewModel::clearAlbumSelection,
                        onDelete = viewModel::deleteSelectedAlbums,
                    )
                } else {
                    LibraryChipsRow(
                        selected = uiState.selectedTab,
                        onSelect = viewModel::selectTab,
                        sort = uiState.sort,
                        onSort = viewModel::setSort,
                    )
                }
                activeImportProgress?.let { progress ->
                    if (progress.total > 0) {
                        val fraction = (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = NamiColors.Shu,
                            trackColor = NamiColors.Ink700,
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = NamiColors.Shu,
                            trackColor = NamiColors.Ink700,
                        )
                    }
                }
                when (uiState.selectedTab) {
                    LibraryTab.TRACKS -> {
                        val serverTracksAll by viewModel.serverTracks.collectAsState()
                        val uniqueServerTracks = remember(serverTracksAll) {
                            serverTracksAll.filter { !viewModel.isLocallyAvailable(it) }
                        }
                        TrackListContent(
                            tracks = tracks,
                            serverTracks = uniqueServerTracks,
                            onPlayServerTrack = viewModel::playServerTrack,
                            listState = trackListState,
                            selectionMode = selectionMode,
                            selectedTrackIds = uiState.selectedTrackIds,
                            recentAlbums = recentAlbums,
                            featuredArtists = featuredArtists,
                            onTrackClick = onTrackClick,
                            onAlbumClick = onAlbumClick,
                            onShowAllAlbums = { viewModel.selectTab(LibraryTab.ALBUMS) },
                            onArtistClick = onArtistClick,
                            onShowAllArtists = { viewModel.selectTab(LibraryTab.ARTISTS) },
                            onAddToPlaylist = { trackId -> addToPlaylistTrackId = trackId },
                            onLikeTrack = { trackId -> viewModel.likeTrack(trackId) },
                            onAddToQueueTrack = { track -> viewModel.addToQueue(track) },
                            onDelete = { trackId -> viewModel.deleteTrack(trackId) },
                            onToggleSelection = { trackId -> viewModel.toggleTrackSelection(trackId) },
                            onSetSelection = { ids -> viewModel.setSelectedTracks(ids) },
                            onRenameTrack = { track -> renameTrack = track },
                            onEditNoteTrack = { track -> noteTrack = track },
                            onEditTagsTrack = { track -> editTagsTrackId = track.id },
                            onShowTrackInfo = onShowTrackInfo,
                            onStartRadio = onStartRadio,
                            onShareCard = onShareCard,
                            onUploadToServer = if (viewModel.isServerActive()) {
                                { track -> viewModel.uploadTrackToServer(track) }
                            } else null,
                            nowPlaying = nowPlaying,
                            sort = uiState.sort,
                        )
                    }
                    LibraryTab.ALBUMS -> AlbumGridContent(
                        viewModel = viewModel,
                        gridState = albumGridState,
                        onAlbumClick = onAlbumClick,
                        albumSelectionMode = albumSelectionMode,
                        selectedAlbumIds = uiState.selectedAlbumIds,
                        onToggleAlbumSelection = { albumId -> viewModel.toggleAlbumSelection(albumId) },
                    )
                    LibraryTab.ARTISTS -> ArtistListContent(viewModel, artistListState, onArtistClick)
                    else -> BrowseContent(
                        groups = uiState.browseGroups,
                        loading = uiState.browseLoading,
                        openedGroup = uiState.openedGroup,
                        openedTracks = uiState.openedGroupTracks,
                        onOpenGroup = viewModel::openBrowseGroup,
                        onCloseGroup = viewModel::closeBrowseGroup,
                        onTrackClick = onTrackClick,
                        nowPlaying = nowPlaying,
                    )
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
                        if (uiState.selectedTab == LibraryTab.ALBUMS) {
                            FloatingActionButton(
                                onClick = { viewModel.createAlbum(onCreated = onAlbumClick) },
                                modifier = Modifier.size(36.dp).padding(bottom = 12.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Outlined.LibraryAdd, contentDescription = "Создать альбом", modifier = Modifier.size(18.dp))
                            }
                        }
                        FloatingActionButton(
                            onClick = onImportZipRequested,
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Outlined.Archive, contentDescription = "Импортировать .zip", modifier = Modifier.size(18.dp))
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                        FloatingActionButton(
                            onClick = onImportFolderRequested,
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = "Импортировать папку", modifier = Modifier.size(18.dp))
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                        FloatingActionButton(
                            onClick = onImportRequested,
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(NamiRadius.Card),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "Импортировать файлы", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }

    renameTrack?.let { track ->
        RenameDialog(
            currentName = track.title,
            title = "Переименовать трек",
            onRename = { newTitle -> viewModel.renameTrack(track.id, newTitle) },
            onDismiss = { renameTrack = null },
        )
    }

    noteTrack?.let { track ->
        NoteDialog(
            trackTitle = track.title,
            currentNote = track.note ?: "",
            onSave = { note -> viewModel.setTrackNote(track.id, note) },
            onDismiss = { noteTrack = null },
        )
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

    if (showBatchEditDialog) {
        TagEditDialog(
            trackCount = uiState.selectedTrackIds.size,
            onSearchMusicBrainz = { title, artist -> viewModel.searchMusicBrainz(title, artist) },
            onSave = { artistName, albumName, year, genre ->
                viewModel.batchEditSelectedTracks(artistName, albumName, year, genre)
                showBatchEditDialog = false
            },
            onDismiss = { showBatchEditDialog = false },
        )
    }

    editTagsTrackId?.let { trackId ->
        TagEditDialog(
            trackCount = 1,
            onSearchMusicBrainz = { title, artist -> viewModel.searchMusicBrainz(title, artist) },
            onSave = { artistName, albumName, year, genre ->
                viewModel.batchEditTracks(listOf(trackId), artistName, albumName, year, genre)
                editTagsTrackId = null
            },
            onDismiss = { editTagsTrackId = null },
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
    onLikeSelected: () -> Unit,
    onEditTags: () -> Unit,
    onUploadToServer: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Outlined.Close, contentDescription = "Отменить выбор", tint = NamiColors.Paper100)
        }
        Text(
            text = "Выбрано: $selectedCount",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Outlined.Done, contentDescription = "Выбрать все", tint = NamiColors.Paper70)
        }
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Действия", tint = NamiColors.Paper70)
        }
    }
    if (showMenu) {
        val actions = buildList {
            add(ContextAction("В плейлист", Icons.Outlined.LibraryAdd, onClick = onAddToPlaylist))
            add(ContextAction("Отметить любимым", Icons.Outlined.FavoriteBorder, onClick = onLikeSelected))
            add(ContextAction("Редактировать теги", Icons.Outlined.Edit, onClick = onEditTags))
            if (onUploadToServer != null) {
                add(ContextAction("Отправить на сервер", Icons.Outlined.CloudUpload, onClick = onUploadToServer))
            }
            add(ContextAction("Удалить", Icons.Outlined.Delete, onClick = onDelete))
        }
        ContextActionSheet(
            onDismiss = { showMenu = false },
            actions = actions,
        )
    }
}

@Composable
private fun AlbumSelectionTopBar(selectedCount: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Outlined.Close, contentDescription = "Отменить выбор", tint = NamiColors.Paper100)
        }
        Text(
            text = "Выбрано: $selectedCount",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = NamiColors.Paper70)
        }
    }
}

private val SORT_LABELS = listOf(
    dev.nami.domain.TrackSort.DATE_ADDED to "Дата добавления",
    dev.nami.domain.TrackSort.TITLE to "Название",
    dev.nami.domain.TrackSort.ARTIST to "Артист",
    dev.nami.domain.TrackSort.YEAR to "Год",
    dev.nami.domain.TrackSort.DURATION to "Длительность",
    dev.nami.domain.TrackSort.PLAY_COUNT to "Прослушивания",
    dev.nami.domain.TrackSort.BPM to "BPM",
    dev.nami.domain.TrackSort.BITRATE to "Битрейт",
    dev.nami.domain.TrackSort.RATING to "Рейтинг",
)

@Composable
private fun LibraryChipsRow(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    sort: dev.nami.domain.TrackSort,
    onSort: (dev.nami.domain.TrackSort) -> Unit,
) {
    val labels = mapOf(
        LibraryTab.TRACKS to "Треки",
        LibraryTab.ALBUMS to "Альбомы",
        LibraryTab.ARTISTS to "Артисты",
        LibraryTab.GENRES to "Жанры",
        LibraryTab.FOLDERS to "Папки",
        LibraryTab.TAGS to "Теги",
        LibraryTab.YEARS to "Годы",
    )
    var showSortSheet by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
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
        if (selected == LibraryTab.TRACKS) {
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Outlined.Sort, contentDescription = "Сортировка", tint = NamiColors.Paper70)
            }
        }
    }
    if (showSortSheet) {
        ContextActionSheet(
            onDismiss = { showSortSheet = false },
            actions = SORT_LABELS.map { (value, label) ->
                ContextAction(
                    if (value == sort) "$label ✓" else label,
                    Icons.Outlined.Sort,
                    onClick = { onSort(value) },
                )
            },
        )
    }
}

/** План.md §15 "алфавитный быстрый скролл". Прыгает к первому УЖЕ ЗАГРУЖЕННОМУ треку на букву -
 * список постраничный (Paging), полного алфавитного индекса по всей библиотеке в БД нет, и
 * строить его ради боковой полоски дороже, чем она стоит. Латиница + цифры в "#", всё остальное
 * (кириллица, кана, кандзи) сваливается в одну группу "他": честной транслитерации каны в ромадзи
 * для сортировки тут нет, а фальшивая (по первому символу) хуже, чем отдельная группа. */
@Composable
private fun AlphabetIndex(letters: List<Char>, onLetter: (Char) -> Unit, modifier: Modifier = Modifier) {
    var heightPx by remember { mutableFloatStateOf(0f) }
    fun letterAt(y: Float) {
        if (heightPx <= 0f || letters.isEmpty()) return
        val index = ((y / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        onLetter(letters[index])
    }
    Column(
        modifier = modifier
            .width(22.dp)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> letterAt(offset.y) },
                    onVerticalDrag = { change, _ -> letterAt(change.position.y) },
                )
            }
            .pointerInput(letters) {
                detectTapGestures { offset -> letterAt(offset.y) }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        letters.forEach { letter ->
            Text(
                text = letter.toString(),
                color = NamiColors.Paper40,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Буква для алфавитного индекса: латиница как есть, цифры - "#", остальное - "他". */
private fun indexLetterOf(text: String): Char {
    val first = text.trimStart().firstOrNull()?.uppercaseChar() ?: return '他'
    return when {
        first in 'A'..'Z' -> first
        first.isDigit() -> '#'
        else -> '他'
    }
}

private val INDEX_LETTERS: List<Char> = listOf('#') + ('A'..'Z').toList() + '他'

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
private const val ARTISTS_PREVIEW_HEADER_KEY = "artists-preview-header"
private const val AUTO_SCROLL_EDGE_DP = 64
private const val AUTO_SCROLL_MAX_PX_PER_TICK = 20f

@Composable
private fun TrackListContent(
    tracks: LazyPagingItems<Track>,
    serverTracks: List<dev.nami.domain.ServerTrackMeta>,
    onPlayServerTrack: (dev.nami.domain.ServerTrackMeta) -> Unit,
    listState: LazyListState,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    recentAlbums: List<AlbumSummary>,
    featuredArtists: List<Artist>,
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onShowAllAlbums: () -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    onShowAllArtists: () -> Unit,
    onAddToPlaylist: (TrackId) -> Unit,
    onLikeTrack: (TrackId) -> Unit,
    onAddToQueueTrack: (Track) -> Unit,
    onDelete: (TrackId) -> Unit,
    onToggleSelection: (TrackId) -> Unit,
    onSetSelection: (Set<TrackId>) -> Unit,
    onRenameTrack: (Track) -> Unit,
    onEditNoteTrack: (Track) -> Unit,
    onEditTagsTrack: (Track) -> Unit,
    onShowTrackInfo: (TrackId) -> Unit,
    onStartRadio: (TrackId) -> Unit,
    onShareCard: (Track) -> Unit,
    onUploadToServer: ((Track) -> Unit)? = null,
    nowPlaying: NowPlayingRow?,
    sort: dev.nami.domain.TrackSort,
) {
    if (tracks.itemCount == 0) {
        if (tracks.loadState.refresh is androidx.paging.LoadState.Loading) {
            LibrarySkeleton()
        } else {
            EmptyLibraryMessage()
        }
        return
    }

    // Drag-to-select, driven entirely from this parent Box (not from TrackListItem's own
    // long-press): a long-press with no further movement selects just that row; keeping the
    // finger down and dragging over other rows extends the selection to the range between the
    // anchor row and the finger's row. This has to be the ONLY long-press detector in the
    // touched region - if TrackListItem also registered its own onLongClick, both detectors
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
            ?.takeIf { it != DISCOGRAPHY_HEADER_KEY && it != ARTISTS_PREVIEW_HEADER_KEY }
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
            // The MiniPlayer's background used to be fully opaque, hiding this: the list's own
            // last row can render a sliver past its own area right where MiniPlayer starts. Now
            // that background is a semi-transparent blur, that sliver shows through it. Clip the
            // list's own container so it never draws there in the first place.
            .clipToBounds()
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
            if (featuredArtists.isNotEmpty()) {
                item(key = ARTISTS_PREVIEW_HEADER_KEY) {
                    ArtistsPreviewSection(artists = featuredArtists, onArtistClick = onArtistClick, onShowAllArtists = onShowAllArtists)
                }
            }
            items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                tracks[index]?.let { track ->
                    TrackListItem(
                        track = track,
                        onClick = {
                            if (track.id != dragAnchorId) {
                                if (selectionMode) onToggleSelection(track.id) else onTrackClick(track.id)
                            }
                        },
                        selectionMode = selectionMode,
                        isSelected = track.id in selectedTrackIds,
                        onAddToPlaylist = if (selectionMode) null else { { onAddToPlaylist(track.id) } },
                        onLikeTrack = if (selectionMode) null else { { onLikeTrack(track.id) } },
                        onAddToQueue = if (selectionMode || nowPlaying == null) null else { { onAddToQueueTrack(track) } },
                        onDelete = if (selectionMode) null else { { onDelete(track.id) } },
                        onRename = if (selectionMode) null else { { onRenameTrack(track) } },
                        onEditNote = if (selectionMode) null else { { onEditNoteTrack(track) } },
                        onEditTags = if (selectionMode) null else { { onEditTagsTrack(track) } },
                        onShowInfo = if (selectionMode) null else { { onShowTrackInfo(track.id) } },
                        onStartRadio = if (selectionMode) null else { { onStartRadio(track.id) } },
                        onShareCard = if (selectionMode) null else { { onShareCard(track) } },
                        onUploadToServer = if (selectionMode || onUploadToServer == null) null else {
                            { onUploadToServer(track) }
                        },
                        isCurrentTrack = track.id == nowPlaying?.trackId,
                        isPlaying = track.id == nowPlaying?.trackId && nowPlaying.isPlaying,
                    )
                }
            }

            if (serverTracks.isNotEmpty()) {
                item {
                    Text(
                        text = "На сервере",
                        style = MaterialTheme.typography.titleSmall,
                        color = NamiColors.Paper70,
                        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp)
                    )
                }
                items(serverTracks, key = { "server_${it.id}" }) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayServerTrack(track) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = "Server track",
                            tint = NamiColors.Paper70,
                            modifier = Modifier.size(24.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = NamiColors.Paper100,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatDuration(track.durationMs),
                            color = NamiColors.Paper40,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        if (sort == dev.nami.domain.TrackSort.TITLE || sort == dev.nami.domain.TrackSort.ARTIST) {
            val scope = rememberCoroutineScope()
            val headerCount = (if (recentAlbums.isNotEmpty()) 1 else 0) + (if (featuredArtists.isNotEmpty()) 1 else 0)
            AlphabetIndex(
                letters = INDEX_LETTERS,
                onLetter = { letter ->
                    val items = tracks.itemSnapshotList.items
                    val target = items.indexOfFirst { track ->
                        val key = if (sort == dev.nami.domain.TrackSort.ARTIST) track.artistName.orEmpty() else track.title
                        indexLetterOf(key) == letter
                    }
                    if (target >= 0) scope.launch { listState.scrollToItem(target + headerCount) }
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            )
        }
    }
}

/** Вкладки Жанры/Папки/Теги/Годы: сначала список групп, тап - треки внутри группы. Отдельного
 * экрана под группу нет - это тот же список на месте вкладки плюс строка "назад", так дешевле
 * и не плодит новых маршрутов навигации. */
@Composable
private fun BrowseContent(
    groups: List<BrowseGroup>,
    loading: Boolean,
    openedGroup: BrowseGroup?,
    openedTracks: List<Track>,
    onOpenGroup: (BrowseGroup) -> Unit,
    onCloseGroup: () -> Unit,
    onTrackClick: (TrackId) -> Unit,
    nowPlaying: NowPlayingRow?,
) {
    if (loading) {
        LibrarySkeleton()
        return
    }
    if (openedGroup != null) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCloseGroup).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("← ${openedGroup.title}", color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn {
                items(openedTracks, key = { it.id.value }) { track ->
                    TrackListItem(
                        track = track,
                        onClick = { onTrackClick(track.id) },
                        isCurrentTrack = track.id == nowPlaying?.trackId,
                        isPlaying = track.id == nowPlaying?.trackId && nowPlaying.isPlaying,
                    )
                }
            }
        }
        return
    }
    if (groups.isEmpty()) {
        EmptyLibraryMessage()
        return
    }
    LazyColumn {
        items(groups, key = { it.key }) { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenGroup(group) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(group.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${group.trackCount}", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
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
private fun ArtistsPreviewSection(
    artists: List<Artist>,
    onArtistClick: (ArtistId) -> Unit,
    onShowAllArtists: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Артисты", color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Всё →",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onShowAllArtists),
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists, key = { it.id.value }) { artist ->
                Column(
                    modifier = Modifier.width(76.dp).clickable { onArtistClick(artist.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val photoModifier = Modifier
                        .size(72.dp)
                        .background(NamiColors.Ink700, androidx.compose.foundation.shape.CircleShape)
                    if (artist.photoPath != null) {
                        coil3.compose.AsyncImage(
                            model = artist.photoPath,
                            contentDescription = artist.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = photoModifier.clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    } else {
                        Box(modifier = photoModifier)
                    }
                    Text(
                        text = artist.name,
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumGridContent(
    viewModel: LibraryViewModel,
    gridState: LazyGridState,
    onAlbumClick: (AlbumId) -> Unit,
    albumSelectionMode: Boolean,
    selectedAlbumIds: Set<AlbumId>,
    onToggleAlbumSelection: (AlbumId) -> Unit,
) {
    val albums = viewModel.albums.collectAsLazyPagingItems()
    if (albums.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 172.dp), state = gridState) {
            items(count = albums.itemCount, key = albums.itemKey { it.id.value }) { index ->
                albums[index]?.let { album ->
                    AlbumGridItem(
                        album = album,
                        onClick = {
                            if (albumSelectionMode) onToggleAlbumSelection(album.id) else onAlbumClick(album.id)
                        },
                        onLongClick = { onToggleAlbumSelection(album.id) },
                        selectionMode = albumSelectionMode,
                        isSelected = album.id in selectedAlbumIds,
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

/** План.md §22.17 "Заметки к треку" - free-text personal comment. Multiline OutlinedTextField
 * (RenameDialog's is deliberately singleLine, wrong shape for this), otherwise same
 * NamiAlertDialog styling as every other text-entry dialog in the app. */
@Composable
private fun NoteDialog(trackTitle: String, currentNote: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(currentNote) }
    dev.nami.core.designsystem.NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заметка: $trackTitle") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Например: когда впервые услышал, кто посоветовал...") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onSave(note.trim().ifBlank { null })
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
