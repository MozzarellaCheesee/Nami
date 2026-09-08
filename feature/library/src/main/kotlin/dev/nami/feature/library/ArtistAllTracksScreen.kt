package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.nami.domain.TrackVersionGrouper
import dev.nami.feature.playlists.AddToPlaylistDialog

/** All of an artist's tracks, most-played first - ArtistDetailScreen's "Треки" section only
 * shows the top 10, this is where "Все →" leads. Same remix/live/acoustic version grouping
 * (П.md §23.21) as AlbumDetailScreen - ordering here is by play count, not track/disc number,
 * but TrackVersionGrouper only keys off title+artist so that doesn't change what collapses. */
@Composable
fun ArtistAllTracksScreen(
    onBack: () -> Unit,
    onPlayTracks: (tracks: List<Track>, artistName: String?, startIndex: Int) -> Unit,
    onAddToQueue: (Track, artistName: String?) -> Unit,
    onShowTrackInfo: (TrackId) -> Unit,
    onCompareVersions: (TrackId, TrackId) -> Unit,
    onStartRadio: (TrackId) -> Unit,
    onShareCard: (Track) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val artistName = uiState.artist?.name
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var editTagsTrackId by remember { mutableStateOf<TrackId?>(null) }
    val allTracks = remember(uiState.tracks) { uiState.tracks.sortedByDescending { it.playCount } }
    val versionGroups = remember(allTracks) { TrackVersionGrouper.group(allTracks) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = artistName ?: "Треки", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn {
            versionGroups.forEach { group ->
                val groupKey = group.first().id.value
                val isExpanded = group.size == 1 || expandedGroups[groupKey] == true
                item(key = groupKey) {
                    val track = group.first()
                    val index = allTracks.indexOf(track)
                    TrackListItem(
                        track = track,
                        onClick = { onPlayTracks(allTracks, artistName, index) },
                        onAddToQueue = if (nowPlaying != null) { { onAddToQueue(track, artistName) } } else null,
                        onAddToPlaylist = { addToPlaylistTrackId = track.id },
                        onLikeTrack = { viewModel.likeTrack(track.id) },
                        onRemoveFromArtist = { viewModel.removeTrackFromArtist(track.id) },
                        onEditTags = { editTagsTrackId = track.id },
                        onShowInfo = { onShowTrackInfo(track.id) },
                        onStartRadio = { onStartRadio(track.id) },
                        onShareCard = { onShareCard(track) },
                        isCurrentTrack = track.id == nowPlaying?.trackId,
                        isPlaying = track.id == nowPlaying?.trackId && nowPlaying?.isPlaying == true,
                    )
                    if (group.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isExpanded) "Свернуть версии" else "+${group.size - 1} версии",
                                color = NamiColors.Shu,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(start = 68.dp, top = 2.dp, bottom = 4.dp)
                                    .clickable { expandedGroups[groupKey] = !isExpanded },
                            )
                            if (group.size == 2) {
                                Text(
                                    text = "· сравнить вслепую",
                                    color = NamiColors.Paper40,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .padding(start = 6.dp, top = 2.dp, bottom = 4.dp)
                                        .clickable { onCompareVersions(group[0].id, group[1].id) },
                                )
                            }
                        }
                    }
                }
                if (isExpanded && group.size > 1) {
                    items(group.drop(1), key = { it.id.value }) { track ->
                        val index = allTracks.indexOf(track)
                        TrackListItem(
                            track = track,
                            onClick = { onPlayTracks(allTracks, artistName, index) },
                            onAddToQueue = if (nowPlaying != null) { { onAddToQueue(track, artistName) } } else null,
                            onAddToPlaylist = { addToPlaylistTrackId = track.id },
                            onLikeTrack = { viewModel.likeTrack(track.id) },
                            onRemoveFromArtist = { viewModel.removeTrackFromArtist(track.id) },
                            onEditTags = { editTagsTrackId = track.id },
                            onShowInfo = { onShowTrackInfo(track.id) },
                            onStartRadio = { onStartRadio(track.id) },
                            onShareCard = { onShareCard(track) },
                            isCurrentTrack = track.id == nowPlaying?.trackId,
                            isPlaying = track.id == nowPlaying?.trackId && nowPlaying?.isPlaying == true,
                        )
                    }
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }

    editTagsTrackId?.let { trackId ->
        TagEditDialog(
            trackCount = 1,
            onSearchMusicBrainz = { title, artist -> viewModel.searchMusicBrainz(title, artist) },
            onSave = { artistNameField, albumName, year, genre ->
                viewModel.batchEditTracks(listOf(trackId), artistNameField, albumName, year, genre)
                editTagsTrackId = null
            },
            onDismiss = { editTagsTrackId = null },
        )
    }
}
