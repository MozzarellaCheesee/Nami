package dev.nami.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiScreenHeader
import dev.nami.core.model.TrackId
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlayerRepository
import dev.nami.domain.ServerAudioRepository
import dev.nami.domain.ServerLibraryRepository
import dev.nami.domain.ServerTrackMeta
import dev.nami.domain.JamRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@HiltViewModel
class ServerLibraryViewModel @Inject constructor(
    private val serverLibraryRepository: ServerLibraryRepository,
    private val serverAudioRepository: ServerAudioRepository,
    private val playerRepository: PlayerRepository,
    jamRepository: JamRepository,
) : ViewModel() {

    var tracks by mutableStateOf<List<ServerTrackMeta>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** id треков, скачивание которых сейчас идёт. */
    var downloading by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** id треков, лежащих в офлайн-кеше. */
    var cached by mutableStateOf<Set<Long>>(emptySet())
        private set
    var artworkFiles by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    private var reloadRequested = false
    private var refreshJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            jamRepository.serverChanges.collect { entities ->
                if ("server_disconnected" in entities) {
                    refreshJob?.cancel()
                    tracks = emptyList()
                    artworkFiles = emptyMap()
                    cached = emptySet()
                    downloading = emptySet()
                    loading = false
                    error = "Устройство отвязано от сервера"
                    return@collect
                }
                if ("tracks" in entities || "albums" in entities || "artists" in entities ||
                    entities.any { it.startsWith("artwork:") }
                ) {
                    refreshJob?.cancel()
                    refreshJob = viewModelScope.launch {
                        delay(150L)
                        load()
                    }
                }
            }
        }
    }

    fun load() {
        if (loading) {
            reloadRequested = true
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                if (!serverLibraryRepository.isServerActive()) {
                    error = "Сервер не подключён"
                    return@launch
                }
                cached = serverLibraryRepository.cachedTrackIds()
                val result = serverLibraryRepository.listTracks()
                if (result == null) {
                    error = "Не удалось загрузить треки"
                    return@launch
                }
                tracks = result
                artworkFiles = artworkFiles.filterKeys { id -> result.any { it.id == id } }
                val artworkDownloads = Semaphore(4)
                result.forEach { track ->
                    launch {
                        artworkDownloads.withPermit {
                            serverLibraryRepository.downloadArtwork(track.id)?.let { file ->
                                artworkFiles = artworkFiles + (track.id to artworkModel(file))
                            }
                        }
                    }
                }
            } finally {
                loading = false
                if (reloadRequested) {
                    reloadRequested = false
                    load()
                }
            }
        }
    }

    fun toggleDownload(track: ServerTrackMeta) {
        if (track.id in downloading) return
        if (track.id in cached) {
            serverLibraryRepository.removeFromCache(track.id)
            cached = cached - track.id
            return
        }
        viewModelScope.launch {
            downloading = downloading + track.id
            try {
                val file = serverLibraryRepository.downloadTrack(track.id)
                if (file != null) cached = cached + track.id
            } finally {
                downloading = downloading - track.id
            }
        }
    }

    fun downloadAll() {
        val pending = tracks.filterNot { it.id in cached || it.id in downloading }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            downloading = downloading + pending.map { it.id }
            val completed = mutableSetOf<Long>()
            val limit = Semaphore(3)
            try {
                kotlinx.coroutines.coroutineScope {
                    pending.forEach { track ->
                        launch {
                            limit.withPermit {
                                if (serverLibraryRepository.downloadTrack(track.id) != null) {
                                    synchronized(completed) { completed += track.id }
                                }
                            }
                        }
                    }
                }
                cached = cached + completed
            } finally {
                downloading = downloading - pending.map { it.id }.toSet()
            }
        }
    }

    fun artworkUrl(trackId: Long): String? = artworkFiles[trackId]

    /** Fragment сохраняет file-path, но меняет ключ памяти Coil после замены картинки. */
    private fun artworkModel(file: java.io.File): String = android.net.Uri.fromFile(file)
        .buildUpon().fragment(file.lastModified().toString()).build().toString()

    fun updateTrack(track: ServerTrackMeta) {
        viewModelScope.launch {
            if (serverLibraryRepository.updateTrack(track)) load()
            else error = "Не удалось изменить трек"
        }
    }

    fun updateArtwork(trackId: Long, uri: String) {
        viewModelScope.launch {
            if (serverLibraryRepository.updateArtwork(trackId, uri)) {
                serverLibraryRepository.cachedArtwork(trackId)?.let { file ->
                    artworkFiles = artworkFiles + (trackId to artworkModel(file))
                }
            } else error = "Не удалось изменить обложку"
        }
    }

    fun playTrack(track: ServerTrackMeta) {
        viewModelScope.launch {
            val cachedFile = serverLibraryRepository.cachedFile(track.id)
            val streamUrl = cachedFile?.let { android.net.Uri.fromFile(it).toString() }
                ?: serverAudioRepository.serverStreamUrl(track.id)
                ?: return@launch
            val artUrl = serverLibraryRepository.cachedArtwork(track.id)?.let { android.net.Uri.fromFile(it).toString() }
                ?: serverAudioRepository.serverArtworkUrl(track.id)
            val playable = PlayableTrack(
                id = TrackId("server_${track.id}"),
                title = track.title,
                artistName = track.artist.ifBlank { null },
                path = streamUrl,
                artworkPath = artUrl,
                durationMs = track.durationMs,
            )
            playerRepository.play(listOf(playable), startIndex = 0, startMs = 0L)
        }
    }

    fun playAll(startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val playables = tracks.map { track ->
                val cachedFile = serverLibraryRepository.cachedFile(track.id)
                val streamUrl = cachedFile?.let { android.net.Uri.fromFile(it).toString() }
                    ?: serverAudioRepository.serverStreamUrl(track.id).orEmpty()
                val artUrl = serverLibraryRepository.cachedArtwork(track.id)?.let { android.net.Uri.fromFile(it).toString() }
                    ?: serverAudioRepository.serverArtworkUrl(track.id)
                PlayableTrack(
                    id = TrackId("server_${track.id}"),
                    title = track.title,
                    artistName = track.artist.ifBlank { null },
                    path = streamUrl,
                    artworkPath = artUrl,
                    durationMs = track.durationMs,
                )
            }
            playerRepository.play(playables, startIndex = startIndex, startMs = 0L)
        }
    }

    var selectedTrackIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    var actionMsg by mutableStateOf<String?>(null)

    fun clearActionMsg() { actionMsg = null }

    fun toggleSelect(trackId: Long) {
        selectedTrackIds = if (trackId in selectedTrackIds) selectedTrackIds - trackId else selectedTrackIds + trackId
    }

    fun selectAll() {
        selectedTrackIds = tracks.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedTrackIds = emptySet()
    }

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch {
            if (serverLibraryRepository.deleteTrack(trackId)) {
                tracks = tracks.filterNot { it.id == trackId }
                cached = cached - trackId
                downloading = downloading - trackId
                selectedTrackIds = selectedTrackIds - trackId
                actionMsg = "Трек удалён с сервера"
            } else {
                actionMsg = "Не удалось удалить трек с сервера"
            }
        }
    }

    fun deleteSelectedTracks() {
        val ids = selectedTrackIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (serverLibraryRepository.deleteTracks(ids)) {
                tracks = tracks.filterNot { it.id in selectedTrackIds }
                cached = cached - selectedTrackIds
                downloading = downloading - selectedTrackIds
                actionMsg = "Удалено треков с сервера: ${ids.size}"
                clearSelection()
            } else {
                actionMsg = "Не удалось удалить выбранные треки"
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerLibraryScreen(
    onBack: () -> Unit,
    viewModel: ServerLibraryViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    var editing by remember { mutableStateOf<ServerTrackMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerTrackMeta?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var artworkTarget by remember { mutableStateOf<Long?>(null) }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = artworkTarget
        artworkTarget = null
        if (id != null && uri != null) viewModel.updateArtwork(id, uri.toString())
    }

    viewModel.actionMsg?.let { msg ->
        LaunchedEffect(msg) {
            delay(3000L)
            viewModel.clearActionMsg()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        if (viewModel.selectedTrackIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Outlined.Close, contentDescription = "Отменить выбор", tint = NamiColors.Paper100)
                }
                Text(
                    text = "Выбрано: ${viewModel.selectedTrackIds.size}",
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = viewModel::selectAll) {
                    Icon(Icons.Outlined.Done, contentDescription = "Выбрать все", tint = NamiColors.Paper70)
                }
                IconButton(onClick = { showBatchDeleteConfirm = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Удалить с сервера", tint = NamiColors.Shu)
                }
            }
        } else {
            NamiScreenHeader(title = "Серверная библиотека", onBack = onBack)
        }

        viewModel.actionMsg?.let { msg ->
            Surface(
                color = NamiColors.Ink800,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
            ) {
                Text(
                    msg,
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        when {
            viewModel.loading && viewModel.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            viewModel.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(viewModel.error ?: "", color = NamiColors.Paper70)
            }
            viewModel.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет треков на сервере", color = NamiColors.Paper70)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Всего треков: ${viewModel.tracks.size}",
                            color = NamiColors.Paper70,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        NamiPill(
                            text = "Слушать всё",
                            onClick = { viewModel.playAll(0) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        NamiPill(
                            text = if (viewModel.downloading.isEmpty()) "Скачать всё" else "Скачивание…",
                            onClick = viewModel::downloadAll,
                        )
                    }
                }
                items(viewModel.tracks, key = { it.id }) { track ->
                    val isSelected = track.id in viewModel.selectedTrackIds
                    val inSelectionMode = viewModel.selectedTrackIds.isNotEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (inSelectionMode) {
                                        viewModel.toggleSelect(track.id)
                                    } else {
                                        viewModel.playTrack(track)
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelect(track.id)
                                },
                            )
                            .background(if (isSelected) NamiColors.Ink800 else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (inSelectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { viewModel.toggleSelect(track.id) },
                                colors = CheckboxDefaults.colors(checkedColor = NamiColors.Shu),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        val artUrl = viewModel.artworkUrl(track.id)
                        AsyncImage(
                            model = artUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(NamiColors.Ink700),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(track.artist.ifBlank { null }, track.album).joinToString(" — "),
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val isDownloading = track.id in viewModel.downloading
                        val isCached = track.id in viewModel.cached
                        if (!inSelectionMode) {
                            IconButton(onClick = { editing = track }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Изменить серверный трек", tint = NamiColors.Paper70)
                            }
                            IconButton(onClick = { viewModel.toggleDownload(track) }, enabled = !isDownloading) {
                                when {
                                    isDownloading -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = NamiColors.Shu,
                                        strokeWidth = 2.dp,
                                    )
                                    isCached -> Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = "В офлайн-кеше, нажмите чтобы удалить",
                                        tint = NamiColors.Shu,
                                    )
                                    else -> Icon(
                                        Icons.Outlined.CloudDownload,
                                        contentDescription = "Скачать в офлайн",
                                        tint = NamiColors.Paper70,
                                    )
                                }
                            }
                            IconButton(onClick = { deleteTarget = track }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Удалить с сервера", tint = NamiColors.Paper40)
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { track ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить с сервера?") },
            text = {
                Text(
                    "Трек «${track.title}» будет удалён из библиотеки сервера и перемещён в корзину на сервере.",
                    color = NamiColors.Paper70,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrack(track.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Удалить", color = NamiColors.Shu)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Отмена", color = NamiColors.Paper70)
                }
            },
            containerColor = NamiColors.Ink800,
            titleContentColor = NamiColors.Paper100,
        )
    }

    if (showBatchDeleteConfirm) {
        val count = viewModel.selectedTrackIds.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("Удалить треки с сервера?") },
            text = {
                Text(
                    "Выбрано треков: $count. Они будут удалены из библиотеки сервера и перемещены в корзину на сервере.",
                    color = NamiColors.Paper70,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedTracks()
                        showBatchDeleteConfirm = false
                    },
                ) {
                    Text("Удалить ($count)", color = NamiColors.Shu)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("Отмена", color = NamiColors.Paper70)
                }
            },
            containerColor = NamiColors.Ink800,
            titleContentColor = NamiColors.Paper100,
        )
    }

    editing?.let { track ->
        var title by remember(track.id) { mutableStateOf(track.title) }
        var artist by remember(track.id) { mutableStateOf(track.artist) }
        var album by remember(track.id) { mutableStateOf(track.album.orEmpty()) }
        var year by remember(track.id) { mutableStateOf(track.year?.toString().orEmpty()) }
        var trackNo by remember(track.id) { mutableStateOf(track.trackNo?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Изменить трек") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Название") })
                    OutlinedTextField(artist, { artist = it }, label = { Text("Исполнитель") })
                    OutlinedTextField(album, { album = it }, label = { Text("Альбом") })
                    OutlinedTextField(year, { year = it.filter { c -> c.isDigit() } }, label = { Text("Год") })
                    OutlinedTextField(trackNo, { trackNo = it.filter { c -> c.isDigit() } }, label = { Text("Номер трека") })
                    NamiPill(text = "Выбрать обложку", modifier = Modifier.padding(top = 12.dp), onClick = {
                        artworkTarget = track.id
                        artworkPicker.launch("image/*")
                    })
                }
            },
            confirmButton = {
                NamiPill(text = "Сохранить", onClick = {
                    if (title.isNotBlank()) viewModel.updateTrack(
                        track.copy(title = title.trim(), artist = artist.trim(), album = album.trim().ifBlank { null }, year = year.toIntOrNull(), trackNo = trackNo.toIntOrNull()),
                    )
                    editing = null
                })
            },
            dismissButton = { NamiPill(text = "Отмена", onClick = { editing = null }) },
        )
    }
}
