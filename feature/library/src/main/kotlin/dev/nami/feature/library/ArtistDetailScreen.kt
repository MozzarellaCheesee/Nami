package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog

private const val TOP_TRACKS_LIMIT = 10
private const val ALBUMS_COLLAPSED_LIMIT = 6
// Compact row's avatar shrinks between these two sizes as the photo header collapses -- a
// simplified stand-in for the literal "photo slides and morphs into the avatar spot" effect,
// which would need cross-layout position tracking; this keeps it scroll-reactive without that.
private val AVATAR_MAX_SIZE = 72.dp
private val AVATAR_MIN_SIZE = 40.dp

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onPlayTracks: (tracks: List<Track>, artistName: String?, startIndex: Int) -> Unit,
    onAddToQueue: (Track, artistName: String?) -> Unit,
    onPickPhotoRequested: (ArtistId) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val artistName = uiState.artist?.name
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showArtistMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var albumsExpanded by remember { mutableStateOf(false) }

    // Most-played first, capped -- a full discography list isn't this screen's job (that's what
    // the album cards below are for); this is meant to read like Spotify's "Popular" section.
    val topTracks = remember(uiState.tracks) { uiState.tracks.sortedByDescending { it.playCount }.take(TOP_TRACKS_LIMIT) }
    val visibleAlbums = if (albumsExpanded) uiState.albums else uiState.albums.take(ALBUMS_COLLAPSED_LIMIT)

    val density = LocalDensity.current
    val headerState = rememberCollapsingHeaderState(maxHeight = 280.dp, minHeight = 120.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            .nestedScroll(headerState.nestedScrollConnection),
    ) {
        // No text/controls overlaid on the photo anymore (that's what used to slide under the
        // back button once the header got small) -- they live in their own row below instead.
        PhotoHeader(
            photoPath = uiState.artist?.photoPath,
            onBack = onBack,
            height = with(density) { headerState.heightPx.toDp() },
        ) {}

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = artistName ?: "",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val avatarSize = AVATAR_MAX_SIZE + (AVATAR_MIN_SIZE - AVATAR_MAX_SIZE) * headerState.collapseFraction
                val avatarModifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(NamiColors.Ink700)
                if (uiState.artist?.photoPath != null) {
                    AsyncImage(
                        model = uiState.artist?.photoPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = avatarModifier,
                    )
                } else {
                    Box(modifier = avatarModifier)
                }
                Spacer(modifier = Modifier.padding(start = 12.dp))
                if (uiState.tracks.isNotEmpty()) {
                    IconButton(
                        onClick = { onPlayTracks(uiState.tracks, artistName, 0) },
                        modifier = Modifier
                            .size(56.dp)
                            .background(NamiColors.Paper100, RoundedCornerShape(18.dp)),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Играть всё", tint = NamiColors.Ink900)
                    }
                }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                IconButton(onClick = { showArtistMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с артистом", tint = NamiColors.Paper100)
                }
            }
        }
        if (showArtistMenu) {
            ContextActionSheet(
                onDismiss = { showArtistMenu = false },
                actions = listOf(
                    ContextAction("Переименовать", Icons.Outlined.Edit) { showRenameDialog = true },
                    ContextAction("Изменить фото", Icons.Outlined.Image) { uiState.artist?.let { onPickPhotoRequested(it.id) } },
                ),
            )
        }
        LazyColumn {
            itemsIndexed(topTracks, key = { _, track -> track.id.value }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { onPlayTracks(topTracks, artistName, index) },
                    onAddToQueue = { onAddToQueue(track, artistName) },
                    onAddToPlaylist = { addToPlaylistTrackId = track.id },
                    isCurrentTrack = track.id == nowPlaying?.trackId,
                    isPlaying = track.id == nowPlaying?.trackId && nowPlaying?.isPlaying == true,
                )
            }
            if (uiState.albums.isNotEmpty()) {
                item(key = "albums-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "Альбомы", color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium)
                        if (uiState.albums.size > ALBUMS_COLLAPSED_LIMIT && !albumsExpanded) {
                            Text(
                                text = "Все →",
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { albumsExpanded = true },
                            )
                        }
                    }
                }
                item(key = "albums-row") {
                    LazyRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                        itemsIndexed(visibleAlbums, key = { _, album -> album.id.value }) { _, album ->
                            AlbumGridItem(
                                album = album,
                                onClick = { onAlbumClick(album.id) },
                                modifier = Modifier.width(140.dp).padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = artistName ?: "",
            title = "Переименовать артиста",
            onRename = { newName -> viewModel.renameArtist(newName) },
            onDismiss = { showRenameDialog = false },
        )
    }
}
