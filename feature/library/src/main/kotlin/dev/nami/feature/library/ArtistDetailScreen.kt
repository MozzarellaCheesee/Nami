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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

private const val TOP_TRACKS_LIMIT = 10
private const val ALBUMS_COLLAPSED_LIMIT = 6
private val HEADER_MAX_HEIGHT = 280.dp
private val HEADER_MIN_HEIGHT = 120.dp
private val AVATAR_SIZE = 40.dp

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onShowDiscography: () -> Unit,
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

    val topTracks = remember(uiState.tracks) { uiState.tracks.sortedByDescending { it.playCount }.take(TOP_TRACKS_LIMIT) }
    val visibleAlbums = uiState.albums.take(ALBUMS_COLLAPSED_LIMIT)

    val density = LocalDensity.current
    val headerState = rememberCollapsingHeaderState(maxHeight = HEADER_MAX_HEIGHT, minHeight = HEADER_MIN_HEIGHT)

    // Layering (bottom to top) is what makes this work: the floating photo first, the header's
    // fade gradient on top of it (so the fade is always visible against the photo, not against
    // nothing), the actual scrolling content next (transparent header-height spacer + opaque
    // body), and the back button last so nothing -- least of all the sliding photo -- ever
    // renders over it.
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var avatarSlotOffset by remember { mutableStateOf(Offset.Zero) }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val headerMaxHeightPx = with(density) { HEADER_MAX_HEIGHT.toPx() }
    val avatarSizePx = with(density) { AVATAR_SIZE.toPx() }
    val progress = headerState.collapseFraction
    val headerHeightDp = with(density) { headerState.heightPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(headerState.nestedScrollConnection)
            .onGloballyPositioned { rootOffset = it.positionInRoot() },
    ) {
        // The floating slide: at progress 0 it's the full-width header rectangle (r4, matching
        // the album-art radius convention) sitting at the very top (0,0); at progress 1 it's a
        // 40dp circle sitting exactly in the reserved slot below. Both endpoints and every point
        // between are driven by the same collapseFraction already resizing the header itself.
        uiState.artist?.photoPath?.let { photoPath ->
            val currentWidthPx = lerp(screenWidthPx, avatarSizePx, progress)
            val currentHeightPx = lerp(headerMaxHeightPx, avatarSizePx, progress)
            val offsetX = lerp(0f, avatarSlotOffset.x, progress)
            val offsetY = lerp(0f, avatarSlotOffset.y, progress)
            val cornerRadiusDp = lerp(4f, with(density) { (minOf(currentWidthPx, currentHeightPx) / 2f).toDp().value }, progress)
            AsyncImage(
                model = photoPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(with(density) { currentWidthPx.toDp() }, with(density) { currentHeightPx.toDp() })
                    .clip(RoundedCornerShape(cornerRadiusDp.dp)),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeightDp)
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to NamiColors.Ink900)),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.fillMaxWidth().height(headerHeightDp))
            Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
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
                        // Empty slot: the real image is the floating element above, drawn on top
                        // of this reserved space once it slides all the way in.
                        Spacer(
                            modifier = Modifier
                                .size(AVATAR_SIZE)
                                .onGloballyPositioned { avatarSlotOffset = it.positionInRoot() - rootOffset },
                        )
                        Spacer(modifier = Modifier.padding(start = 12.dp))
                        // Play/overflow slide in and fade in together with the avatar landing,
                        // instead of sitting there statically the whole time.
                        val slideInPx = with(density) { (1f - progress) * 24.dp.toPx() }
                        if (uiState.tracks.isNotEmpty()) {
                            IconButton(
                                onClick = { onPlayTracks(uiState.tracks, artistName, 0) },
                                modifier = Modifier
                                    .size(56.dp)
                                    .graphicsLayer { translationX = slideInPx; alpha = progress }
                                    .background(NamiColors.Paper100, RoundedCornerShape(18.dp)),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Играть всё", tint = NamiColors.Ink900)
                            }
                        }
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        IconButton(
                            onClick = { showArtistMenu = true },
                            modifier = Modifier.graphicsLayer { translationX = slideInPx; alpha = progress },
                        ) {
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
                                Text(text = "Дискография", color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Все →",
                                    color = NamiColors.Paper70,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clickable(onClick = onShowDiscography),
                                )
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
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 12.dp),
        ) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
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
