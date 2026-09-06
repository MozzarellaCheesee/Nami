package dev.nami.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryAdd
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
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

    val tracks = uiState.results.filterIsInstance<SearchResult.TrackResult>()
    val albums = uiState.results.filterIsInstance<SearchResult.AlbumResult>()
    val artists = uiState.results.filterIsInstance<SearchResult.ArtistResult>()

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
                focusedIndicatorColor = NamiColors.Shu,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = NamiColors.Shu,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )

        if (uiState.query.isBlank()) {
            if (uiState.recentQueries.isNotEmpty()) {
                SectionLabel("Недавнее")
                LazyRow(
                    contentPadding = PaddingValuesHorizontal20,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.recentQueries) { query ->
                        Text(
                            text = query,
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .background(NamiColors.Ink800, RoundedCornerShape(20.dp))
                                .clickable { viewModel.onRecentQueryClick(query) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                Text(text = "Начните вводить, чтобы искать", color = NamiColors.Paper40)
            }
        } else if (uiState.results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                Text(text = "Ничего не нашлось", color = NamiColors.Paper40)
            }
        } else {
            LazyColumn {
                if (tracks.isNotEmpty()) {
                    item { SectionLabel("Треки") }
                    items(tracks, key = { "track-${it.id.value}" }) { track ->
                        TrackResultRow(
                            track = track,
                            onClick = { onTrackClick(track.id) },
                            onAddToPlaylist = { addToPlaylistTrackId = track.id },
                        )
                    }
                }
                if (albums.isNotEmpty()) {
                    item { SectionLabel("Альбомы") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValuesHorizontal20,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(albums, key = { "album-${it.id.value}" }) { album ->
                                AlbumResultCard(album = album, onClick = { onAlbumClick(album.id) })
                            }
                        }
                    }
                }
                if (artists.isNotEmpty()) {
                    item { SectionLabel("Артисты") }
                    items(artists, key = { "artist-${it.id.value}" }) { artist ->
                        ArtistResultRow(artist = artist, onClick = { onArtistClick(artist.id) })
                    }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }
}

private val PaddingValuesHorizontal20 = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun TrackResultRow(track: SearchResult.TrackResult, onClick: () -> Unit, onAddToPlaylist: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(NamiColors.Ink700),
        ) {
            if (track.artworkPath != null) {
                AsyncImage(model = track.artworkPath, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track.artistName?.let { name ->
                Text(text = name, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(Icons.Outlined.LibraryAdd, contentDescription = "В плейлист", tint = NamiColors.Paper70)
        }
    }
}

@Composable
private fun AlbumResultCard(album: SearchResult.AlbumResult, onClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(NamiColors.Ink700),
        ) {
            if (album.artworkPath != null) {
                AsyncImage(model = album.artworkPath, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            text = album.title,
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        album.artistName?.let { name ->
            Text(text = name, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ArtistResultRow(artist: SearchResult.ArtistResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(NamiColors.Ink700),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = NamiColors.Paper40)
        }
        Text(
            text = artist.name,
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
