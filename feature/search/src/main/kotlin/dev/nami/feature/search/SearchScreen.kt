package dev.nami.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.domain.SearchResult

@Composable
fun SearchScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
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
}

@Composable
private fun SearchResultRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(text = subtitle, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun SearchResult.resultKey(): String = when (this) {
    is SearchResult.TrackResult -> "track-${id.value}"
    is SearchResult.AlbumResult -> "album-${id.value}"
    is SearchResult.ArtistResult -> "artist-${id.value}"
}
