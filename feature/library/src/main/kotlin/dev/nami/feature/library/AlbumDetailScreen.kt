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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showAlbumMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        PhotoHeader(photoPath = uiState.album?.artworkPath, onBack = onBack) {
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
                            Icon(Icons.Filled.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper100)
                        }
                        DropdownMenu(expanded = showAlbumMenu, onDismissRequest = { showAlbumMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Добавить в очередь") },
                                onClick = {
                                    showAlbumMenu = false
                                    uiState.tracks.forEach { onAddToQueue(it) }
                                },
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
                )
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }
}
