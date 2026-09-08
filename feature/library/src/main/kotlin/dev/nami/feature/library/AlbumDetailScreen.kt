package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Star
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.RenameDialog
import dev.nami.core.designsystem.rememberCollapsingHeaderState
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
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiType
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.feature.playlists.AddToPlaylistDialog
import kotlin.math.roundToInt

private val HEADER_MIN_HEIGHT = 56.dp
private val AVATAR_SIZE = 40.dp

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onShuffleTracks: (tracks: List<Track>) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPickCoverRequested: (AlbumId) -> Unit,
    onDeleted: () -> Unit,
    onShowTrackInfo: (TrackId) -> Unit,
    onShowAlbumInfo: (AlbumId) -> Unit,
    onCompareVersions: (TrackId, TrackId) -> Unit,
    onStartRadio: (TrackId) -> Unit,
    onShareCard: (Track) -> Unit,
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
    var editTagsTrackId by remember { mutableStateOf<TrackId?>(null) }

    val density = LocalDensity.current
    // The cover is square (aspectRatio 1f everywhere else it's shown - grid, Info screen); the
    // header needs to match, not a fixed 280dp that reads as a wide rectangle on any screen wider
    // than that. Screen width IS the cover's width here (it fills it), so that's also its height.
    val headerMaxHeight = LocalConfiguration.current.screenWidthDp.dp
    val headerState = rememberCollapsingHeaderState(maxHeight = headerMaxHeight, minHeight = HEADER_MIN_HEIGHT)
    val listState = rememberLazyListState()
    // Only two resting states - fully expanded or fully collapsed. Without this, releasing
    // mid-scroll left the header (and the sliding cover, whose size/shape/position are all
    // driven by collapseFraction) stuck halfway.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) headerState.snapToNearestEdge()
    }

    // Same pattern as ArtistDetailScreen: floating cover, then gradient (sharing its exact
    // offset/size/clip so they never desync mid-slide), then the scrollable content with a
    // transparent header-height spacer, back button drawn last - see that screen for the full
    // rationale on each of these.
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var avatarSlotOffset by remember { mutableStateOf(Offset.Zero) }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val headerMaxHeightPx = with(density) { headerMaxHeight.toPx() }
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
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(
                        text = uiState.album?.title ?: "",
                        color = NamiColors.Paper100,
                        style = NamiType.ScreenTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    uiState.album?.year?.let { year ->
                        Text(text = year.toString(), color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                    }
                    // Avatar slot (the floating cover's landing target) plus Play/overflow laid
                    // out in a real Row right next to it - previously these lived in a manually
                    // offset-and-sized Box floating over the cover, sized/positioned off the
                    // COVER's own shrink math (currentWidthPx/currentHeightPx) instead of the
                    // ACTUAL on-screen header height, so mid-scroll (header already shorter than
                    // its max, cover not yet circular) that box still claimed the full original
                    // header area and drew over the title. Laying them out for real, in-flow,
                    // next to the avatar spacer can't overlap anything above it - Compose does
                    // that math, not manual offsets.
                    Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            modifier = Modifier
                                .size(
                                    width = with(density) { (avatarSizePx * progress).toDp() },
                                    height = with(density) { (avatarSizePx * progress).toDp() },
                                )
                                .onGloballyPositioned { avatarSlotOffset = it.positionInRoot() - rootOffset },
                        )
                        // Fades in only once the cover is mostly a circle - while it's still
                        // large, Play/overflow live on the cover image itself instead (below).
                        if (progress > 0.6f) {
                            Row(
                                modifier = Modifier
                                    .graphicsLayer { alpha = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f) }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (uiState.tracks.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onPlayTracks(uiState.tracks, 0) },
                                        modifier = Modifier.size(36.dp).background(NamiColors.Paper100, RoundedCornerShape(NamiRadius.Button)),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Играть альбом", tint = NamiColors.Ink900, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onShuffleTracks(uiState.tracks) },
                                        modifier = Modifier.padding(start = 4.dp),
                                    ) {
                                        Icon(Icons.Outlined.Shuffle, contentDescription = "Перемешать", tint = NamiColors.Paper100)
                                    }
                                }
                                IconButton(onClick = { showAlbumMenu = true }, modifier = Modifier.padding(start = 4.dp)) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper100)
                                }
                            }
                        }
                    }
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
                            ContextAction("Информация об альбоме", Icons.Outlined.Info) { uiState.album?.let { onShowAlbumInfo(it.id) } },
                        ),
                    )
                }
                // Группировка версий (План.md §23.21) - remix/live/instrumental/acoustic of the
                // same track collapse into one row with a "+N версий" expand toggle, instead of
                // each cluttering the list as its own separate entry.
                val versionGroups = remember(uiState.tracks) { dev.nami.domain.TrackVersionGrouper.group(uiState.tracks) }
                val expandedGroups = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
                LazyColumn(state = listState) {
                    versionGroups.forEach { group ->
                        val groupKey = group.first().id.value
                        val isExpanded = group.size == 1 || expandedGroups[groupKey] == true
                        item(key = groupKey) {
                            val track = group.first()
                            val index = uiState.tracks.indexOf(track)
                            TrackListItem(
                                track = track,
                                onClick = { onPlayTracks(uiState.tracks, index) },
                                onAddToQueue = if (nowPlaying != null) { { onAddToQueue(track) } } else null,
                                onAddToPlaylist = { addToPlaylistTrackId = track.id },
                                onLikeTrack = { viewModel.likeTrack(track.id) },
                                onRemoveFromAlbum = { viewModel.removeTrackFromAlbum(track.id) },
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
                                val index = uiState.tracks.indexOf(track)
                                TrackListItem(
                                    track = track,
                                    onClick = { onPlayTracks(uiState.tracks, index) },
                                    onAddToQueue = if (nowPlaying != null) { { onAddToQueue(track) } } else null,
                                    onAddToPlaylist = { addToPlaylistTrackId = track.id },
                                    onLikeTrack = { viewModel.likeTrack(track.id) },
                                    onRemoveFromAlbum = { viewModel.removeTrackFromAlbum(track.id) },
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
        }

        run {
            // See ArtistDetailScreen's identical block for why: bleeds the expanded cover up
            // into the status bar/cutout inset instead of showing Ink900 through the gap that
            // displayCutoutPadding() (applied once, up in NamiNavHost) leaves even when the
            // status bar itself is hidden. Tapers to 0 as the cover collapses into the avatar slot.
            // rootOffset.y is the real measured push-down from NamiNavHost's ambient
            // statusBarsPadding()+displayCutoutPadding() - see ArtistDetailScreen's identical
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

            // Play/overflow live on the cover itself while it's still large, then crossfade to
            // the smaller inline pair next to the avatar slot once it's mostly a circle (see the
            // Row next to the avatar Spacer above) - gone from composition (not just alpha 0)
            // past the threshold so they're not still tappable once invisible.
            if (progress < 0.6f) {
                // Height tracks headerState.heightPx - the ACTUAL current on-screen header
                // height (shrinks continuously as the list scrolls, all the way from
                // HEADER_MAX_HEIGHT to HEADER_MIN_HEIGHT) - not headerMaxHeightPx (which stays
                // constant) and not currentWidthPx/currentHeightPx (the cover's own shrink toward
                // the avatar, a different curve). Its bottom edge lands exactly where the title
                // Column starts (that Column sits right after a Spacer(headerHeightDp) of the
                // same height), so this box can never draw over the title regardless of scroll
                // position. Crossfades with the inline avatar-row buttons above (progress > 0.6).
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, rootOffset.y.roundToInt()) }
                        .size(with(density) { screenWidthPx.toDp() }, with(density) { (headerState.heightPx + rootOffset.y).toDp() }),
                    // Bottom of the gradient (near the box's own bottom edge), not its top - the
                    // box height already tracks headerState.heightPx (the ACTUAL current header
                    // height, not a fixed guess), so its bottom edge lands exactly where the title
                    // starts without drifting mid-scroll the way the old BottomEnd version used to
                    // (that one was sized off the cover's own different shrink curve instead).
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Row(
                        // 44dp bottom clearance - roughly the title line's own height plus a
                        // little air, so the buttons sit above where "Название альбома" actually
                        // renders instead of right at the box edge (== title's top edge, no gap).
                        modifier = Modifier.graphicsLayer { alpha = (1f - progress / 0.6f).coerceIn(0f, 1f) }.padding(bottom = 64.dp, end = 12.dp),
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
                            Spacer(modifier = Modifier.padding(start = 8.dp))
                            IconButton(
                                onClick = { onShuffleTracks(uiState.tracks) },
                                modifier = Modifier.background(NamiColors.Ink900.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
                            ) {
                                Icon(Icons.Outlined.Shuffle, contentDescription = "Перемешать", tint = NamiColors.Paper100)
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

        // Кружок под стрелкой: она лежит поверх обложки, и на светлой картинке белая стрелка
        // без подложки просто исчезала.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .background(NamiColors.Ink900.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
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

    editTagsTrackId?.let { trackId ->
        TagEditDialog(
            trackCount = 1,
            onSearchMusicBrainz = { title, artist -> viewModel.searchMusicBrainz(title, artist) },
            onSave = { artistName, albumName, year, genre ->
                viewModel.batchEditTracks(listOf(trackId), artistName, albumName, year, genre)
                editTagsTrackId = null
            },
            onDismiss = { editTagsTrackId = null },
        )
    }
}
