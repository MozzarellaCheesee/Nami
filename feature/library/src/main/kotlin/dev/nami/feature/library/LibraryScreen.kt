package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog

@Composable
fun LibraryScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    onImportRequested: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column {
            LibraryChipsRow(selected = uiState.selectedTab, onSelect = viewModel::selectTab)
            when (uiState.selectedTab) {
                LibraryTab.TRACKS -> TrackListContent(
                    viewModel = viewModel,
                    onTrackClick = onTrackClick,
                    onAddToPlaylist = { trackId -> addToPlaylistTrackId = trackId },
                )
                LibraryTab.ALBUMS -> AlbumGridContent(viewModel, onAlbumClick)
                LibraryTab.ARTISTS -> ArtistListContent(viewModel, onArtistClick)
            }
        }

        FloatingActionButton(
            onClick = onImportRequested,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Импортировать файлы")
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackId = trackId, onDismiss = { addToPlaylistTrackId = null })
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
    onTrackClick: (TrackId) -> Unit,
    onAddToPlaylist: (TrackId) -> Unit,
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    if (tracks.itemCount == 0) {
        EmptyLibraryMessage()
    } else {
        LazyColumn {
            items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                tracks[index]?.let { track ->
                    TrackListItem(
                        track = track,
                        onClick = { onTrackClick(track.id) },
                        onAddToPlaylist = { onAddToPlaylist(track.id) },
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
