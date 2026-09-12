package dev.nami.feature.library

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@HiltViewModel
class ServerLibraryViewModel @Inject constructor(
    private val serverLibraryRepository: ServerLibraryRepository,
    private val serverAudioRepository: ServerAudioRepository,
    private val playerRepository: PlayerRepository,
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

    fun load() {
        if (loading) return
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
                val artworkDownloads = Semaphore(4)
                result.forEach { track ->
                    launch {
                        artworkDownloads.withPermit {
                            serverLibraryRepository.downloadArtwork(track.id)?.let { file ->
                                artworkFiles = artworkFiles + (track.id to android.net.Uri.fromFile(file).toString())
                            }
                        }
                    }
                }
            } finally {
                loading = false
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
                    artworkFiles = artworkFiles + (trackId to android.net.Uri.fromFile(file).toString())
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
}

@Composable
fun ServerLibraryScreen(
    onBack: () -> Unit,
    viewModel: ServerLibraryViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    var editing by remember { mutableStateOf<ServerTrackMeta?>(null) }
    var artworkTarget by remember { mutableStateOf<Long?>(null) }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = artworkTarget
        artworkTarget = null
        if (id != null && uri != null) viewModel.updateArtwork(id, uri.toString())
    }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        NamiScreenHeader(title = "Серверная библиотека", onBack = onBack)

        when {
            viewModel.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.playTrack(track) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                    }
                }
            }
        }
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
