package dev.nami.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.domain.SearchResult
import dev.nami.feature.playlists.AddToPlaylistDialog

@Composable
fun SearchScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900).imePadding()) {
        TextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Поиск", color = NamiColors.Paper70) },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
        LazyColumn {
            items(uiState.results, key = { it.resultKey() }) { result ->
                when (result) {
                    is SearchResult.TrackResult -> SearchResultRow(
                        title = result.title,
                        subtitle = result.artistName,
                        onClick = { onTrackClick(result.id) },
                        onAddToPlaylist = { addToPlaylistTrackId = result.id },
                    )
                    is SearchResult.AlbumResult -> SearchResultRow(
                        title = result.title,
                        subtitle = result.artistName,
                        onClick = { onAlbumClick(result.id) },
                    )
                    is SearchResult.ArtistResult -> SearchResultRow(
                        title = result.name,
                        subtitle = null,
                        onClick = { onArtistClick(result.id) },
                    )
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackId = trackId, onDismiss = { addToPlaylistTrackId = null })
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = "В плейлист", tint = NamiColors.Paper70)
            }
        }
    }
}

private fun SearchResult.resultKey(): String = when (this) {
    is SearchResult.TrackResult -> "track-${id.value}"
    is SearchResult.AlbumResult -> "album-${id.value}"
    is SearchResult.ArtistResult -> "artist-${id.value}"
}
