package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onPlayTracks: (tracks: List<Track>, artistName: String?, startIndex: Int) -> Unit,
    onAddToQueue: (Track, artistName: String?) -> Unit,
    onPickPhotoRequested: (ArtistId) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val artistName = uiState.artist?.name
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showArtistMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val headerState = rememberCollapsingHeaderState(maxHeight = 360.dp, minHeight = 170.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            .nestedScroll(headerState.nestedScrollConnection),
    ) {
        PhotoHeader(
            photoPath = uiState.artist?.photoPath,
            onBack = onBack,
            height = with(density) { headerState.heightPx.toDp() },
        ) {
            Column {
                Text(
                    text = artistName ?: "",
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.tracks.isNotEmpty()) {
                        IconButton(
                            onClick = { onPlayTracks(uiState.tracks, artistName, 0) },
                            modifier = Modifier
                                .size(64.dp)
                                .background(NamiColors.Paper100, RoundedCornerShape(20.dp)),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Играть всё", tint = NamiColors.Ink900)
                        }
                    }
                    Spacer(modifier = Modifier.padding(start = 12.dp))
                    IconButton(onClick = { showArtistMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с артистом", tint = NamiColors.Paper100)
                    }
                }
            }
        }
        if (showArtistMenu) {
            ContextActionSheet(
                onDismiss = { showArtistMenu = false },
                actions = listOf(
                    ContextAction("Переименовать", Icons.Outlined.Edit) { showRenameDialog = true },
                    ContextAction("Изменить фото", Icons.Outlined.Image) { uiState.artist?.let { onPickPhotoRequested(it.id) } },
                ),
            )
        }
        LazyRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            itemsIndexed(uiState.albums, key = { _, album -> album.id.value }) { _, album ->
                AlbumGridItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    modifier = Modifier.width(140.dp).padding(8.dp),
                )
            }
        }
        LazyColumn {
            itemsIndexed(uiState.tracks, key = { _, track -> track.id.value }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { onPlayTracks(uiState.tracks, artistName, index) },
                    onAddToQueue = { onAddToQueue(track, artistName) },
                    onAddToPlaylist = { addToPlaylistTrackId = track.id },
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
            currentName = artistName ?: "",
            title = "Переименовать артиста",
            onRename = { newName -> viewModel.renameArtist(newName) },
            onDismiss = { showRenameDialog = false },
        )
    }
}
