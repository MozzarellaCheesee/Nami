package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
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

/** All of an artist's tracks, most-played first -- ArtistDetailScreen's "Треки" section only
 * shows the top 10, this is where "Все →" leads. */
@Composable
fun ArtistAllTracksScreen(
    onBack: () -> Unit,
    onPlayTracks: (tracks: List<Track>, artistName: String?, startIndex: Int) -> Unit,
    onAddToQueue: (Track, artistName: String?) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val artistName = uiState.artist?.name
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    val allTracks = remember(uiState.tracks) { uiState.tracks.sortedByDescending { it.playCount } }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = artistName ?: "Треки", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn {
            itemsIndexed(allTracks, key = { _, track -> track.id.value }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { onPlayTracks(allTracks, artistName, index) },
                    onAddToQueue = { onAddToQueue(track, artistName) },
                    onAddToPlaylist = { addToPlaylistTrackId = track.id },
                    onRemoveFromArtist = { viewModel.removeTrackFromArtist(track.id) },
                    isCurrentTrack = track.id == nowPlaying?.trackId,
                    isPlaying = track.id == nowPlaying?.trackId && nowPlaying?.isPlaying == true,
                )
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }
}
