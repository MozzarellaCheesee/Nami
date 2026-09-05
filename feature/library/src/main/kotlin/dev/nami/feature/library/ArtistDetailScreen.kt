package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.TrackId

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onTrackClick: (TrackId) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
        }
        Text(
            text = uiState.artist?.name ?: "",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            items(uiState.albums, key = { it.id.value }) { album ->
                AlbumGridItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        LazyColumn {
            items(uiState.tracks, key = { it.id.value }) { track ->
                TrackListItem(track = track, onClick = { onTrackClick(track.id) })
            }
        }
    }
}
