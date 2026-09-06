package dev.nami.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
            placeholder = { Text("Поиск треков, альбомов, исполнителей", color = NamiColors.Paper40) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = NamiColors.Paper70) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NamiColors.Ink800,
                unfocusedContainerColor = NamiColors.Ink800,
                focusedTextColor = NamiColors.Paper100,
                unfocusedTextColor = NamiColors.Paper100,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = NamiColors.Shu,
            ),
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
        if (uiState.query.isBlank()) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                Text(text = "Начните вводить, чтобы искать", color = NamiColors.Paper40)
            }
        } else if (uiState.results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                Text(text = "Ничего не нашлось", color = NamiColors.Paper40)
            }
        } else {
            LazyColumn {
                items(uiState.results, key = { it.resultKey() }) { result ->
                    when (result) {
                        is SearchResult.TrackResult -> SearchResultRow(
                            icon = Icons.Outlined.MusicNote,
                            title = result.title,
                            subtitle = result.artistName,
                            onClick = { onTrackClick(result.id) },
                            onAddToPlaylist = { addToPlaylistTrackId = result.id },
                        )
                        is SearchResult.AlbumResult -> SearchResultRow(
                            icon = Icons.Outlined.Album,
                            title = result.title,
                            subtitle = result.artistName,
                            onClick = { onAlbumClick(result.id) },
                        )
                        is SearchResult.ArtistResult -> SearchResultRow(
                            icon = Icons.Outlined.Person,
                            title = result.name,
                            subtitle = null,
                            onClick = { onArtistClick(result.id) },
                        )
                    }
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }
}

@Composable
private fun SearchResultRow(
    icon: ImageVector,
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
        Box(
            modifier = Modifier.background(NamiColors.Ink700, RoundedCornerShape(10.dp)).padding(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.padding(0.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Outlined.LibraryAdd, contentDescription = "В плейлист", tint = NamiColors.Paper70)
            }
        }
    }
}

private fun SearchResult.resultKey(): String = when (this) {
    is SearchResult.TrackResult -> "track-${id.value}"
    is SearchResult.AlbumResult -> "album-${id.value}"
    is SearchResult.ArtistResult -> "artist-${id.value}"
}
