package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Star
import dev.nami.core.designsystem.NamiAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog
import kotlin.math.roundToInt

private val HEADER_MAX_HEIGHT = 280.dp
private val HEADER_MIN_HEIGHT = 56.dp
private val AVATAR_SIZE = 40.dp

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPickCoverRequested: (AlbumId) -> Unit,
    onDeleted: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var showAlbumMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddTracksDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPickArtistDialog by remember { mutableStateOf(false) }
    var showEditYearDialog by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val headerState = rememberCollapsingHeaderState(maxHeight = HEADER_MAX_HEIGHT, minHeight = HEADER_MIN_HEIGHT)
    val listState = rememberLazyListState()
    // Only two resting states -- fully expanded or fully collapsed. Without this, releasing
    // mid-scroll left the header (and the sliding cover, whose size/shape/position are all
    // driven by collapseFraction) stuck halfway.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) headerState.snapToNearestEdge()
    }

    // Same pattern as ArtistDetailScreen: floating cover, then gradient (sharing its exact
    // offset/size/clip so they never desync mid-slide), then the scrollable content with a
    // transparent header-height spacer, back button drawn last -- see that screen for the full
    // rationale on each of these.
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
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.fillMaxWidth().height(headerHeightDp))
            Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = uiState.album?.title ?: "",
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    uiState.album?.year?.let { year ->
                        Text(text = year.toString(), color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                    }
                    // Just the slide-target anchor now -- play/overflow moved onto the cover
                    // image itself (see the floating image below).
                    Spacer(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .size(
                                width = with(density) { (avatarSizePx * progress).toDp() },
                                height = with(density) { (avatarSizePx * progress).toDp() },
                            )
                            .onGloballyPositioned { avatarSlotOffset = it.positionInRoot() - rootOffset },
                    )
                }
                if (showAlbumMenu) {
                    ContextActionSheet(
                        onDismiss = { showAlbumMenu = false },
                        actions = listOf(
                            ContextAction("Добавить в очередь", Icons.Outlined.PlaylistAdd) { uiState.tracks.forEach { onAddToQueue(it) } },
                            ContextAction("Переименовать", Icons.Outlined.Edit) { showRenameDialog = true },
                            ContextAction("Год выпуска", Icons.Outlined.Edit) { showEditYearDialog = true },
                            ContextAction("Изменить обложку", Icons.Outlined.Image) { uiState.album?.let { onPickCoverRequested(it.id) } },
                            ContextAction("Добавить треки", Icons.Outlined.LibraryAdd) { showAddTracksDialog = true },
                            ContextAction("Артисты", Icons.Outlined.Person) { showPickArtistDialog = true },
                            ContextAction(
                                if (uiState.album?.isSingle == true) "Убрать метку \"сингл\"" else "Отметить как сингл",
                                Icons.Outlined.Star,
                            ) { uiState.album?.let { viewModel.setIsSingle(!it.isSingle) } },
                            ContextAction("Удалить альбом", Icons.Outlined.Delete) { showDeleteConfirm = true },
                        ),
                    )
                }
                LazyColumn(state = listState) {
                    itemsIndexed(uiState.tracks, key = { _, track -> track.id.value }) { index, track ->
                        TrackListItem(
                            track = track,
                            onClick = { onPlayTracks(uiState.tracks, index) },
                            onAddToQueue = if (nowPlaying != null) { { onAddToQueue(track) } } else null,
                            onAddToPlaylist = { addToPlaylistTrackId = track.id },
                            onRemoveFromAlbum = { viewModel.removeTrackFromAlbum(track.id) },
                            isCurrentTrack = track.id == nowPlaying?.trackId,
                            isPlaying = track.id == nowPlaying?.trackId && nowPlaying?.isPlaying == true,
                        )
                    }
                }
            }
        }

        run {
            // See ArtistDetailScreen's identical block for why: bleeds the expanded cover up
            // into the status bar/cutout inset instead of showing Ink900 through the gap that
            // displayCutoutPadding() (applied once, up in NamiNavHost) leaves even when the
            // status bar itself is hidden. Tapers to 0 as the cover collapses into the avatar slot.
            // rootOffset.y is the real measured push-down from NamiNavHost's ambient
            // statusBarsPadding()+displayCutoutPadding() -- see ArtistDetailScreen's identical
            // block for the full rationale (a composition-local-based guess at the inset was
            // wrong here; this is the actual value, not a guess).
            val bleed = rootOffset.y * (1f - progress)
            val currentWidthPx = lerp(screenWidthPx, avatarSizePx, progress)
            val currentHeightPx = lerp(headerMaxHeightPx, avatarSizePx, progress) + bleed
            val offsetX = lerp(0f, avatarSlotOffset.x, progress)
            val offsetY = lerp(0f, avatarSlotOffset.y, progress) - bleed
            val cornerRadiusDp = lerp(4f, with(density) { (minOf(currentWidthPx, currentHeightPx) / 2f).toDp().value }, progress)
            val slideModifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(with(density) { currentWidthPx.toDp() }, with(density) { currentHeightPx.toDp() })
                .clip(RoundedCornerShape(cornerRadiusDp.dp))
            val coverPath = uiState.album?.artworkPath
            if (coverPath != null) {
                val request = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                    .data(coverPath)
                    .size(screenWidthPx.roundToInt(), headerMaxHeightPx.roundToInt())
                    .build()
                Box(modifier = slideModifier) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - progress }
                            .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to NamiColors.Ink900)),
                    )
                }
            } else {
                Box(modifier = slideModifier.background(NamiColors.Ink700))
            }

            // Play/overflow live on the cover itself now instead of a dedicated row under the
            // title -- fade out well before the cover collapses into the small 40dp avatar (a
            // 56dp button doesn't fit inside that; not reusing slideModifier's own clip here
            // avoids them visibly getting chewed into the shrinking circle), and gone from
            // composition (not just alpha 0) past the threshold so they're not still tappable.
            if (progress < 0.5f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        .size(with(density) { currentWidthPx.toDp() }, with(density) { currentHeightPx.toDp() }),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Row(
                        modifier = Modifier.graphicsLayer { alpha = ((0.5f - progress) / 0.5f).coerceIn(0f, 1f) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.tracks.isNotEmpty()) {
                            IconButton(
                                onClick = { onPlayTracks(uiState.tracks, 0) },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(NamiColors.Paper100, RoundedCornerShape(18.dp)),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Играть альбом", tint = NamiColors.Ink900)
                            }
                        }
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        IconButton(
                            onClick = { showAlbumMenu = true },
                            modifier = Modifier.background(NamiColors.Ink900.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper100)
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
            currentName = uiState.album?.title ?: "",
            title = "Переименовать альбом",
            onRename = { newTitle -> viewModel.renameAlbum(newTitle) },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showAddTracksDialog) {
        uiState.album?.let { album ->
            AddTracksToAlbumDialog(albumId = album.id, onDismiss = { showAddTracksDialog = false })
        }
    }

    if (showEditYearDialog) {
        EditYearDialog(
            currentYear = uiState.album?.year,
            onSave = { year -> viewModel.setYear(year) },
            onDismiss = { showEditYearDialog = false },
        )
    }

    if (showPickArtistDialog) {
        ManageAlbumArtistsDialog(
            artists = uiState.artists,
            onAdd = { artistId -> viewModel.addArtist(artistId) },
            onRemove = { artistId -> viewModel.removeArtist(artistId) },
            onDismiss = { showPickArtistDialog = false },
        )
    }

    if (showDeleteConfirm) {
        NamiAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить альбом?") },
            text = { Text("Треки альбома переместятся в корзину. Их можно будет восстановить.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlbum()
                    showDeleteConfirm = false
                    onDeleted()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") } },
        )
    }
}
