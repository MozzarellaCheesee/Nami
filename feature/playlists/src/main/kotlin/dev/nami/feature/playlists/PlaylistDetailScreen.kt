package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.LikedPlaylistCover
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.RenameDialog
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.Track

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onExportRequested: (PlaylistId) -> Unit,
    onPickCoverRequested: (PlaylistId) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val playlist = uiState.playlist
    val isLiked = playlist?.isLiked == true
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                text = playlist?.name ?: "",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp).let {
                    if (isLiked) it else it.clickable { showRenameDialog = true }
                },
            )
            // Любимые треки: no rename/cover/delete, so the whole overflow menu (which only ever
            // held those plus export) collapses to just the export action inline instead of a
            // sheet with one item in it.
            if (isLiked) {
                IconButton(onClick = { onExportRequested(viewModel.playlistId) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Экспорт в .m3u8", tint = NamiColors.Paper70)
                }
            } else {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с плейлистом", tint = NamiColors.Paper100)
                }
            }
        }
        if (showMenu) {
            ContextActionSheet(
                onDismiss = { showMenu = false },
                actions = listOf(
                    ContextAction("Изменить обложку", Icons.Outlined.Image) { onPickCoverRequested(viewModel.playlistId) },
                    ContextAction("Экспорт в .m3u8", Icons.Outlined.Share) { onExportRequested(viewModel.playlistId) },
                    ContextAction("Удалить плейлист", Icons.Outlined.Delete) { showDeleteDialog = true },
                ),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            when {
                isLiked -> LikedPlaylistCover(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp)),
                )
                playlist?.coverPath != null -> AsyncImage(
                    model = playlist.coverPath,
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp)).background(NamiColors.Ink700),
                )
                else -> Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp)).background(NamiColors.Ink700),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${uiState.tracks.size} " + tracksWord(uiState.tracks.size),
                        color = NamiColors.Paper70,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (uiState.tracks.isNotEmpty()) {
                    IconButton(
                        onClick = { onPlayTracks(uiState.tracks, 0) },
                        modifier = Modifier.size(56.dp).background(NamiColors.Paper100, RoundedCornerShape(18.dp)),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Играть", tint = NamiColors.Ink900)
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            itemsIndexed(uiState.tracks, key = { _, track -> track.id.value }) { index, track ->
                PlaylistTrackRow(
                    track = track,
                    onClick = { onPlayTracks(uiState.tracks, index) },
                    onRemove = { viewModel.removeTrack(track.id) },
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = playlist?.name ?: "",
            title = "Переименовать плейлист",
            onRename = { newName -> viewModel.rename(newName) },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        NamiAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить плейлист?") },
            text = { Text("Треки останутся в библиотеке. Плейлист будет в корзине 30 дней.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(onDeleted)
                    showDeleteDialog = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } },
        )
    }
}

private fun tracksWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
}

/** Styled to match TrackListItem's (feature:library) look and feel -- same artwork-box size,
 * text colors/styles, ContextActionSheet-based overflow -- without actually depending on that
 * module: feature:library already depends on feature:playlists (AddToPlaylistDialog), so the
 * reverse dependency needed to reuse TrackListItem directly isn't available without a bigger
 * module reshuffle out of scope here. */
@Composable
private fun PlaylistTrackRow(track: Track, onClick: () -> Unit, onRemove: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track.albumArtworkPath != null) {
            AsyncImage(
                model = track.albumArtworkPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(NamiColors.Ink700),
            )
        } else {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(NamiColors.Ink700))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track.artistName?.let { name ->
                Text(text = name, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper40)
        }
    }
    if (showMenu) {
        ContextActionSheet(
            onDismiss = { showMenu = false },
            actions = listOf(
                ContextAction("Убрать из плейлиста", Icons.Outlined.Close, onRemove),
            ),
        )
    }
}
