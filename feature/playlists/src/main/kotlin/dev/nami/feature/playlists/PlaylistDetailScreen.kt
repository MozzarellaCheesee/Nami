package dev.nami.feature.playlists

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tune
import dev.nami.core.model.TrackId
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
import dev.nami.core.designsystem.LikedPlaylistCover
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiType
import dev.nami.core.designsystem.RenameDialog
import dev.nami.core.designsystem.rememberCollapsingHeaderState
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.Track
import kotlin.math.roundToInt

// Same collapsing-header pattern as AlbumDetailScreen/ArtistDetailScreen (feature:library) --
// floating square cover that shrinks into a circular avatar next to the title as the track list
// scrolls up. Not shared code with those screens: feature:library already depends on
// feature:playlists (AddToPlaylistDialog), so the reverse dependency needed to reuse their
// composables directly isn't available - CollapsingHeaderState itself lives in
// core:designsystem and IS shared (see that file).
private val HEADER_MIN_HEIGHT = 56.dp
private val AVATAR_SIZE = 40.dp

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onPlayTracks: (tracks: List<Track>, startIndex: Int) -> Unit,
    onExportRequested: (PlaylistId) -> Unit,
    onPickCoverRequested: (PlaylistId) -> Unit,
    onEditSmartPlaylist: (PlaylistId) -> Unit,
    onShuffleTracks: (tracks: List<Track>) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val playlist = uiState.playlist
    val isLiked = playlist?.isLiked == true
    val isSmart = playlist?.isSmart == true
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    // Обложка - квадрат во всех остальных местах (сетка/AddToPlaylist), а не зашитая 280dp,
    // которая на экране шире 280dp читалась как приплюснутый прямоугольник и не доходила до
    // такой же высоты, что у альбомов - тот же приём, что уже у AlbumDetailScreen: ширина экрана
    // это же и есть ширина обложки, значит и высота. В ландшафте на телефоне ширина экрана -
    // длинная сторона, поэтому дополнительно ограничена долей реальной высоты - иначе обложка
    // без ограничения занимала почти весь экран и список было нечем прокрутить.
    val configuration = LocalConfiguration.current
    val headerMaxHeight = minOf(configuration.screenWidthDp, (configuration.screenHeightDp * 0.55f).toInt()).dp
    val headerState = rememberCollapsingHeaderState(maxHeight = headerMaxHeight, minHeight = HEADER_MIN_HEIGHT)
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) headerState.snapToNearestEdge()
    }

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
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = playlist?.name ?: "",
                        color = NamiColors.Paper100,
                        style = NamiType.ScreenTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = Int.MAX_VALUE)
                            .let { if (isLiked) it else it.clickable { showRenameDialog = true } },
                    )
                    // Мета-строка: тип плейлиста цветом (умный - Ai), дальше счёт треков. Раньше
                    // "умный" нигде на самом экране не отображался, хотя от него зависит, можно ли
                    // вообще убрать трек руками.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (isSmart) {
                            Text(
                                text = "УМНЫЙ",
                                color = NamiColors.Ai,
                                style = NamiType.Caption,
                                modifier = Modifier
                                    .background(NamiColors.Ai.copy(alpha = 0.14f), RoundedCornerShape(NamiRadius.Chip))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        Text(
                            text = "${uiState.tracks.size} " + tracksWord(uiState.tracks.size),
                            color = NamiColors.Paper40,
                            style = NamiType.Secondary,
                        )
                    }
                    Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            modifier = Modifier
                                .size(
                                    width = with(density) { (avatarSizePx * progress).toDp() },
                                    height = with(density) { (avatarSizePx * progress).toDp() },
                                )
                                .onGloballyPositioned { avatarSlotOffset = it.positionInRoot() - rootOffset },
                        )
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
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Играть", tint = NamiColors.Ink900, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onShuffleTracks(uiState.tracks) },
                                        modifier = Modifier.padding(start = 4.dp),
                                    ) {
                                        Icon(Icons.Outlined.Shuffle, contentDescription = "Перемешать", tint = NamiColors.Paper100)
                                    }
                                }
                                if (isLiked) {
                                    IconButton(onClick = { onExportRequested(viewModel.playlistId) }, modifier = Modifier.padding(start = 4.dp)) {
                                        Icon(Icons.Outlined.Share, contentDescription = "Экспорт в .m3u8", tint = NamiColors.Paper100)
                                    }
                                } else {
                                    IconButton(onClick = { showMenu = true }, modifier = Modifier.padding(start = 4.dp)) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с плейлистом", tint = NamiColors.Paper100)
                                    }
                                }
                            }
                        }
                    }
                }
                if (showMenu) {
                    ContextActionSheet(
                        onDismiss = { showMenu = false },
                        actions = listOfNotNull(
                            // Обложку можно сменить у любого плейлиста кроме "Любимых" (у него
                            // своя фиксированная обложка-иконка) - умный плейлист не исключение,
                            // раньше вместо неё был только пункт правил.
                            ContextAction("Изменить обложку", Icons.Outlined.Image) { onPickCoverRequested(viewModel.playlistId) },
                            if (isSmart) {
                                ContextAction("Изменить правила", Icons.Outlined.Edit) { onEditSmartPlaylist(viewModel.playlistId) }
                            } else {
                                null
                            },
                            ContextAction("Настройки воспроизведения", Icons.Outlined.Tune) { showPlaybackSettings = true },
                            ContextAction("Экспорт в .m3u8", Icons.Outlined.Share) { onExportRequested(viewModel.playlistId) },
                            ContextAction("Удалить плейлист", Icons.Outlined.Delete) { showDeleteDialog = true },
                        ),
                    )
                }
                if (showPlaybackSettings) {
                    // П.md §20. Каждый тап переключает одну настройку; лист закрывается, как и
                    // любой ContextActionSheet - своего экрана ради трёх переключателей не надо.
                    fun tristate(value: Boolean?) = when (value) {
                        null -> "не задано"
                        true -> "включать"
                        false -> "выключать"
                    }
                    ContextActionSheet(
                        onDismiss = { showPlaybackSettings = false },
                        actions = listOf(
                            ContextAction(
                                if (playlist?.eqGainsCsv == null) {
                                    "Эквалайзер: не задан - запомнить текущий"
                                } else {
                                    "Эквалайзер: запомнен - перезаписать текущим"
                                },
                                Icons.Outlined.Tune,
                            ) { viewModel.rememberCurrentEq() },
                            ContextAction("Эквалайзер: забыть", Icons.Outlined.Close) { viewModel.clearEq() },
                            ContextAction("Кроссфейд: ${tristate(playlist?.crossfadeEnabled)}", Icons.Outlined.Tune) {
                                viewModel.cycleCrossfade()
                            },
                            ContextAction("Перемешивать при запуске: ${tristate(playlist?.shuffleOnStart)}", Icons.Outlined.Tune) {
                                viewModel.cycleShuffleOnStart()
                            },
                        ),
                    )
                }
                LazyColumn(state = listState) {
                    itemsIndexed(uiState.tracks, key = { _, track -> track.id.value }) { index, track ->
                        PlaylistTrackRow(
                            track = track,
                            onClick = { onPlayTracks(uiState.tracks, index) },
                            // Smart playlist tracks aren't rows in playlist_tracks (they're
                            // computed by SmartPlaylistEvaluator) - there's nothing to remove.
                            onRemove = if (isSmart) null else { { viewModel.removeTrack(track.id) } },
                            modifier = Modifier.animateItem(),
                            chainCandidates = uiState.tracks,
                            onSetChain = { next -> viewModel.setChain(track.id, next) },
                            position = index + 1,
                        )
                    }
                }
            }
        }

        run {
            // Same bleed/slide/crossfade math as AlbumDetailScreen's identical block - see that
            // screen for the full rationale on each term.
            val bleed = rootOffset.y * (1f - progress)
            val currentWidthPx = lerp(screenWidthPx, avatarSizePx, progress)
            val currentHeightPx = lerp(headerMaxHeightPx, avatarSizePx, progress) + bleed
            val offsetX = lerp(0f, avatarSlotOffset.x, progress)
            val offsetY = lerp(0f, avatarSlotOffset.y, progress) - bleed
            val cornerRadiusDp = lerp(8f, with(density) { (minOf(currentWidthPx, currentHeightPx) / 2f).toDp().value }, progress)
            val slideModifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(with(density) { currentWidthPx.toDp() }, with(density) { currentHeightPx.toDp() })
                .clip(RoundedCornerShape(cornerRadiusDp.dp))
            when {
                isLiked -> Box(modifier = slideModifier) {
                    LikedPlaylistCover(modifier = Modifier.fillMaxSize())
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - progress }
                            .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to NamiColors.Ink900)),
                    )
                }
                playlist?.coverPath != null -> {
                    val request = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                        .data(playlist.coverPath)
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
                }
                else -> Box(modifier = slideModifier.background(NamiColors.Ink700))
            }

            if (progress < 0.6f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, rootOffset.y.roundToInt()) }
                        .size(with(density) { screenWidthPx.toDp() }, with(density) { (headerState.heightPx + rootOffset.y).toDp() }),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Row(
                        modifier = Modifier.graphicsLayer { alpha = (1f - progress / 0.6f).coerceIn(0f, 1f) }.padding(bottom = 64.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.tracks.isNotEmpty()) {
                            IconButton(
                                onClick = { onPlayTracks(uiState.tracks, 0) },
                                modifier = Modifier.size(56.dp).background(NamiColors.Paper100, RoundedCornerShape(18.dp)),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Играть", tint = NamiColors.Ink900)
                            }
                            Spacer(modifier = Modifier.padding(start = 8.dp))
                            IconButton(
                                onClick = { onShuffleTracks(uiState.tracks) },
                                modifier = Modifier.background(NamiColors.Ink900.copy(alpha = 0.4f), CircleShape),
                            ) {
                                Icon(Icons.Outlined.Shuffle, contentDescription = "Перемешать", tint = NamiColors.Paper100)
                            }
                        }
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        if (isLiked) {
                            IconButton(
                                onClick = { onExportRequested(viewModel.playlistId) },
                                modifier = Modifier.background(NamiColors.Ink900.copy(alpha = 0.4f), CircleShape),
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = "Экспорт в .m3u8", tint = NamiColors.Paper100)
                            }
                        } else {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.background(NamiColors.Ink900.copy(alpha = 0.4f), CircleShape),
                            ) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с плейлистом", tint = NamiColors.Paper100)
                            }
                        }
                    }
                }
            }
        }

        // Кружок под стрелкой: она лежит поверх обложки, и на светлой картинке белая стрелка без
        // подложки просто исчезала.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .background(NamiColors.Ink900.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = playlist?.name ?: "",
            title = "Переименовать плейлист",
            onRename = { newName -> viewModel.rename(newName) },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        NamiAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить плейлист?") },
            text = { Text("Треки останутся в библиотеке. Плейлист будет в корзине 30 дней.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(onDeleted)
                    showDeleteDialog = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } },
        )
    }
}

private fun tracksWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
}

/** Styled to match TrackListItem's (feature:library) look and feel - same artwork-box size,
 * text colors/styles, ContextActionSheet-based overflow - without actually depending on that
 * module: feature:library already depends on feature:playlists (AddToPlaylistDialog), so the
 * reverse dependency needed to reuse TrackListItem directly isn't available without a bigger
 * module reshuffle out of scope here. */
@Composable
private fun PlaylistTrackRow(
    track: Track,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Кандидаты в преемники - только треки этого же плейлиста: полноценный поиск по библиотеке
    // ради связки двух соседних треков был бы отдельным экраном, а цепочку почти всегда строят
    // именно внутри плейлиста, который сейчас перед глазами.
    chainCandidates: List<Track> = emptyList(),
    onSetChain: ((TrackId?) -> Unit)? = null,
    position: Int? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showChainPicker by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Номер позиции: в плейлисте порядок - смысл, а не случайность (цепочки, "играть с
        // этого места"), и по одним обложкам понять, какой это по счёту трек, было нельзя.
        if (position != null) {
            Text(
                text = "$position",
                color = NamiColors.Paper40,
                style = NamiType.TechData,
                modifier = Modifier.size(width = 24.dp, height = 20.dp).padding(end = 4.dp),
            )
        }
        if (track.albumArtworkPath != null) {
            AsyncImage(
                model = track.albumArtworkPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NamiRadius.AlbumArt)).background(NamiColors.Ink700),
            )
        } else {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NamiRadius.AlbumArt)).background(NamiColors.Ink700))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = track.title, color = NamiColors.Paper100, style = NamiType.TrackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track.artistName?.let { name ->
                Text(text = name, color = NamiColors.Paper40, style = NamiType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRemove != null) {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper40)
            }
        }
    }
    if (showMenu && onRemove != null) {
        ContextActionSheet(
            onDismiss = { showMenu = false },
            actions = listOfNotNull(
                ContextAction("Убрать из плейлиста", Icons.Outlined.Close, onClick = onRemove),
                onSetChain?.let {
                    ContextAction("Всегда ставить следом…", Icons.Outlined.Link) { showChainPicker = true }
                },
                onSetChain?.let { setChain ->
                    ContextAction("Убрать цепочку", Icons.Outlined.LinkOff) { setChain(null) }
                },
            ),
        )
    }
    if (showChainPicker && onSetChain != null) {
        ContextActionSheet(
            onDismiss = { showChainPicker = false },
            actions = chainCandidates
                .filter { it.id != track.id }
                .map { candidate ->
                    ContextAction(candidate.title, Icons.Outlined.Link) { onSetChain(candidate.id) }
                },
        )
    }
}
