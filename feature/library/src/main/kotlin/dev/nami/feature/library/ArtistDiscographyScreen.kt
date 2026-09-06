package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Shuffle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog

/**
 * Full discography for one artist: every album as its own block (cover, badge/artist/title/year,
 * play/shuffle/overflow, tracks) instead of the artist page's compact horizontal row.
 */
@Composable
fun ArtistDiscographyScreen(
    onBack: () -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onPlayTracks: (tracks: List<Track>, artistName: String?, startIndex: Int) -> Unit,
    onAddToQueue: (Track, artistName: String?) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val artistName = uiState.artist?.name
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var albumMenuId by remember { mutableStateOf<AlbumId?>(null) }
    val tracksByAlbum = remember(uiState.tracks) { uiState.tracks.groupBy { it.albumId } }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = "Дискография", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn {
            items(uiState.albums, key = { it.id.value }) { album ->
                val albumTracks = remember(album.id, tracksByAlbum) {
                    tracksByAlbum[album.id].orEmpty().sortedBy { it.trackNo ?: Int.MAX_VALUE }
                }
                AlbumDiscographyBlock(
                    album = album,
                    tracks = albumTracks,
                    artistName = artistName,
                    nowPlayingTrackId = nowPlaying?.trackId,
                    isPlayingNow = nowPlaying?.isPlaying == true,
                    onAlbumClick = { onAlbumClick(album.id) },
                    onPlayAlbum = { onPlayTracks(albumTracks, artistName, 0) },
                    onShufflePlayAlbum = { onPlayTracks(albumTracks.shuffled(), artistName, 0) },
                    onMoreClick = { albumMenuId = album.id },
                    onTrackClick = { index -> onPlayTracks(albumTracks, artistName, index) },
                    onAddToQueue = { track -> onAddToQueue(track, artistName) },
                    onAddToPlaylist = { trackId -> addToPlaylistTrackId = trackId },
                )
            }
        }
    }

    albumMenuId?.let { albumId ->
        ContextActionSheet(
            onDismiss = { albumMenuId = null },
            actions = listOf(
                ContextAction("Редактировать альбом", Icons.Outlined.Edit) {
                    albumMenuId = null
                    onAlbumClick(albumId)
                },
                ContextAction("Добавить в очередь", Icons.Outlined.PlaylistAdd) {
                    tracksByAlbum[albumId].orEmpty().forEach { onAddToQueue(it, artistName) }
                },
            ),
        )
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }
}

@Composable
private fun AlbumDiscographyBlock(
    album: AlbumSummary,
    tracks: List<Track>,
    artistName: String?,
    nowPlayingTrackId: TrackId?,
    isPlayingNow: Boolean,
    onAlbumClick: () -> Unit,
    onPlayAlbum: () -> Unit,
    onShufflePlayAlbum: () -> Unit,
    onMoreClick: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (TrackId) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(onClick = onAlbumClick),
        ) {
            val artworkModifier = Modifier.fillMaxSize().background(NamiColors.Ink700, RoundedCornerShape(4.dp))
            if (album.artworkPath != null) {
                AsyncImage(
                    model = album.artworkPath,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = artworkModifier,
                )
            } else {
                Box(modifier = artworkModifier)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to NamiColors.Ink900)),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).clickable(onClick = onAlbumClick)) {
            Text(
                text = if (album.isSingle) "СИНГЛ" else "АЛЬБОМ",
                color = NamiColors.Ai,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = album.title,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val subtitle = listOfNotNull(artistName, album.year?.toString()).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(text = subtitle, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPlayAlbum,
                    modifier = Modifier.size(48.dp).background(NamiColors.Paper100, RoundedCornerShape(16.dp)),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Играть альбом", tint = NamiColors.Ink900)
                }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                IconButton(onClick = onShufflePlayAlbum) {
                    Icon(Icons.Outlined.Shuffle, contentDescription = "Перемешать и играть", tint = NamiColors.Paper100)
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onMoreClick) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper40)
                }
            }
        }
        tracks.forEachIndexed { index, track ->
            TrackListItem(
                track = track,
                onClick = { onTrackClick(index) },
                onAddToQueue = if (nowPlayingTrackId != null) { { onAddToQueue(track) } } else null,
                onAddToPlaylist = { onAddToPlaylist(track.id) },
                isCurrentTrack = track.id == nowPlayingTrackId,
                isPlaying = track.id == nowPlayingTrackId && isPlayingNow,
            )
        }
    }
}
