package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPickCoverRequested: (AlbumId) -> Unit,
    onDeleted: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showAlbumMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddTracksDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val headerState = rememberCollapsingHeaderState(maxHeight = 360.dp, minHeight = 120.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            .nestedScroll(headerState.nestedScrollConnection),
    ) {
        PhotoHeader(
            photoPath = uiState.album?.artworkPath,
            onBack = onBack,
            height = with(density) { headerState.heightPx.toDp() },
        ) {
            Column {
                Text(
                    text = uiState.album?.title ?: "",
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.headlineSmall,
                )
                uiState.album?.year?.let { year ->
                    Text(text = year.toString(), color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.tracks.isNotEmpty()) {
                        IconButton(
                            onClick = { onPlayTracks(uiState.tracks, 0) },
                            modifier = Modifier
                                .size(64.dp)
                                .background(NamiColors.Paper100, RoundedCornerShape(20.dp)),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Играть альбом", tint = NamiColors.Ink900)
                        }
                    }
                    Spacer(modifier = Modifier.padding(start = 12.dp))
                    Box {
                        IconButton(onClick = { showAlbumMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper100)
                        }
                        DropdownMenu(expanded = showAlbumMenu, onDismissRequest = { showAlbumMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Добавить в очередь") },
                                onClick = {
                                    showAlbumMenu = false
                                    uiState.tracks.forEach { onAddToQueue(it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                onClick = { showAlbumMenu = false; showRenameDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Изменить обложку") },
                                onClick = {
                                    showAlbumMenu = false
                                    uiState.album?.let { onPickCoverRequested(it.id) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Добавить треки") },
                                onClick = { showAlbumMenu = false; showAddTracksDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (uiState.album?.isSingle == true) "Убрать метку \"сингл\"" else "Отметить как сингл") },
                                onClick = {
                                    showAlbumMenu = false
                                    uiState.album?.let { viewModel.setIsSingle(!it.isSingle) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить альбом") },
                                onClick = { showAlbumMenu = false; showDeleteConfirm = true },
                            )
                        }
                    }
                }
            }
        }
        LazyColumn {
            itemsIndexed(uiState.tracks, key = { _, track -> track.id.value }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { onPlayTracks(uiState.tracks, index) },
                    onAddToQueue = { onAddToQueue(track) },
                    onAddToPlaylist = { addToPlaylistTrackId = track.id },
                    onRemoveFromAlbum = { viewModel.removeTrackFromAlbum(track.id) },
                    isCurrentTrack = track.id == nowPlaying?.trackId,
                    isPlaying = track.id == nowPlaying?.trackId && nowPlaying?.isPlaying == true,
                )
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = uiState.album?.title ?: "",
            title = "Переименовать альбом",
            onRename = { newTitle -> viewModel.renameAlbum(newTitle) },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showAddTracksDialog) {
        uiState.album?.let { album ->
            AddTracksToAlbumDialog(albumId = album.id, onDismiss = { showAddTracksDialog = false })
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить альбом?") },
            text = { Text("Треки альбома переместятся в корзину. Их можно будет восстановить.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlbum()
                    showDeleteConfirm = false
                    onDeleted()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") } },
        )
    }
}
