package dev.nami.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.request.ImageRequest
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.fullBlockClickable
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 120

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    onCollapse: () -> Unit,
    onQueueClick: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by viewModel.playbackState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val playing = state as? PlaybackState.Playing
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Local-only stub -- no "favorites" concept exists in the domain layer yet, so this doesn't
    // persist across tracks/sessions. Resets whenever the playing track changes.
    var isFavorite by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(queue.nowPlaying?.id) { isFavorite = false }
    // Stubs -- ExoPlayer supports real shuffle/repeat, but wiring actual queue-reordering and
    // playback-loop behavior is out of scope here; this is just the visual toggle per the mockup.
    var isShuffleOn by remember { mutableStateOf(false) }
    var isRepeatOn by remember { mutableStateOf(false) }

    // Shared by the swipe gesture and the chevron button so both dismiss paths always finish
    // the slide-down themselves before popping -- see the comment on the swipe branch below.
    fun collapseAnimated() {
        scope.launch {
            animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
            onCollapse()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) },
    ) {
        // Ambient background: the current track's own artwork, heavily blurred, dimmed under a
        // dark scrim for text legibility -- for atmosphere, per Дизайн.md's "тихое" restraint
        // this stays a backdrop, never competing with the actual artwork/controls on top of it.
        val backgroundArtworkPath = queue.nowPlaying?.artworkPath
        if (backgroundArtworkPath != null) {
            AsyncImage(
                model = backgroundArtworkPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(64.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900.copy(alpha = if (backgroundArtworkPath != null) 0.72f else 1f)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                },
                onDragStopped = { velocity ->
                    if (dragOffsetY > dismissThresholdPx || velocity > 2000f) {
                        // Finish sliding fully off-screen ourselves, THEN pop -- popping first
                        // (tried before) let the Library screen and MiniPlayer underneath
                        // become visible/interactive while this screen was still mid-slide on
                        // top of them, and stacked AnimatedVisibility's own exit slide on top of
                        // this one's offset, compounding into a visible gap/glitch. Popping only
                        // once this is already fully off-screen makes AnimatedVisibility's exit
                        // (now instant, see NamiNavHost) invisible -- there's nothing left to see.
                        animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
                        onCollapse()
                    } else {
                        animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                    }
                },
            )
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).fullBlockClickable(shape = CircleShape, onClick = ::collapseAnimated),
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Свернуть", tint = NamiColors.Paper100)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).fullBlockClickable(shape = CircleShape) { showOverflowMenu = true },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper100)
            }
        }
        // Slide-up sheet instead of a dropdown -- this is the pattern requested for every
        // "..." menu app-wide (track/album/playlist/artist, each with its own action set).
        // This is the first one wired; the rest (library track rows, album/artist detail
        // overflow, playlist rows) are a separate follow-up, not done in this pass.
        if (showOverflowMenu) {
            // Stub actions: no queue-source/playlist-origin data is plumbed through yet (see
            // Дизайн.md §4.3's "Из плейлиста ..." label, also skipped for the same reason), and
            // no audio-chain/sleep-timer screens exist yet either.
            ContextActionSheet(
                onDismiss = { showOverflowMenu = false },
                actions = listOf(
                    ContextAction("Аудиотракт", Icons.Outlined.QueueMusic) {},
                    ContextAction("Таймер сна", Icons.Outlined.DarkMode) {},
                ),
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        // No scroll: everything must fit on-screen at once. Reduced top gap before the artwork
        // frees up the vertical room this needs, instead of a scrollable body.
        // 3-page window: 0 = previous, 1 = current, 2 = next. HorizontalPager owns the drag/fling
        // math itself (a hand-rolled offset carousel here kept shipping subtle positioning bugs),
        // and keeps neighbor pages composed via beyondViewportPageCount so their artwork is
        // already loading well before a swipe reaches them.
        val pagerState = rememberPagerState(initialPage = 1) { 3 }
        LaunchedEffectSettlePage(pagerState, queue.previousTrack != null, queue.upcoming.isNotEmpty(), viewModel)

        // Peek: pages are narrower than the pager itself (contentPadding), so the previous/next
        // cover's edge shows at rest, not just once you start dragging. The pager's own height is
        // set to the resulting (smaller) page width, not the full container width, so each page
        // is still a perfect square instead of a square-container's worth of height stuffed into
        // a narrower page.
        val peekDp = 28.dp
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
            val pageWidth = maxWidth - peekDp * 2
            HorizontalPager(
                state = pagerState,
                pageSpacing = 16.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = peekDp),
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageWidth)
                    .clipToBounds(),
            ) { page ->
                val track = when (page) {
                    0 -> queue.previousTrack
                    2 -> queue.upcoming.firstOrNull()?.track
                    else -> queue.nowPlaying
                }
                val accentColor = rememberArtworkAccentColor(track?.artworkPath)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    // Soft accent glow behind the artwork, per Дизайн.md's "мягкое свечение
                    // цветом акцента" -- Compose has no CSS box-shadow, so a blurred radial
                    // gradient sitting behind the artwork approximates it (Modifier.blur needs
                    // API 31+; on older devices it degrades to an unblurred soft-edged gradient,
                    // still reading as a glow). Color is that page's own dominant/vibrant tone
                    // (via Palette), falling back to --shu while loading or if extraction fails.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(accentColor.copy(alpha = 0.55f), accentColor.copy(alpha = 0f)),
                                ),
                            )
                            .blur(32.dp),
                    )
                    NowPlayingArtwork(
                        artworkPath = track?.artworkPath,
                        contentDescription = track?.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = queue.nowPlaying?.title ?: "Ничего не играет",
                color = NamiColors.Paper100,
                maxLines = 1,
                modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
            )
            // Stub, see isFavorite's declaration above -- not persisted anywhere yet.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(32.dp).fullBlockClickable(shape = CircleShape) { isFavorite = !isFavorite },
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "Убрать из избранного" else "В избранное",
                    tint = if (isFavorite) NamiColors.Shu else NamiColors.Paper100,
                )
            }
        }
        queue.nowPlaying?.artistName?.let { artistName ->
            Text(text = artistName, color = NamiColors.Paper70)
        }
        val durationMs = playing?.durationMs ?: 0L
        val actualPositionMs = playing?.positionMs ?: 0L
        val actualProgress = if (durationMs > 0) (actualPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        // While dragging, the time label below should track the finger, not real playback
        // position (which only jumps once the drag ends and seek() actually fires).
        var previewProgress by remember { mutableStateOf<Float?>(null) }
        val progress = previewProgress ?: actualProgress
        val positionMs = (progress * durationMs).toLong()
        WaveformScrubber(
            seedKey = queue.nowPlaying?.id?.value ?: "",
            progress = actualProgress,
            onSeek = { fraction -> viewModel.seek((fraction * durationMs).toLong()) },
            onProgressPreview = { fraction -> previewProgress = fraction },
            onPreviewEnd = { previewProgress = null },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatDuration(positionMs),
                color = NamiColors.Paper70,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "-" + formatDuration((durationMs - positionMs).coerceAtLeast(0L)),
                color = NamiColors.Paper70,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
        }
        // Five rounded-square blocks, shrinking away from the center: play (72) > prev/next (56)
        // > shuffle/repeat (44). Shuffle/repeat are visual-only stubs (see isShuffleOn/isRepeatOn
        // above) -- ExoPlayer supports both for real, but wiring actual queue reshuffling and
        // loop behavior is a separate task.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportBlock(
                icon = Icons.Outlined.Shuffle,
                size = 44.dp,
                active = isShuffleOn,
                contentDescription = "Перемешать",
                onClick = { isShuffleOn = !isShuffleOn },
            )
            TransportBlock(
                icon = Icons.Rounded.SkipPrevious,
                size = 56.dp,
                contentDescription = "Предыдущий",
                onClick = {
                    // A previous track to show -> animate the pager, same as a swipe (forces the
                    // actual previous track). Nothing to show -> fall back to the button's own
                    // restart-if-elapsed semantics with no animation (nothing to slide to).
                    if (queue.previousTrack != null) {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    } else {
                        viewModel.skipPrevious()
                    }
                },
            )
            TransportBlock(
                icon = if (playing?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                size = 72.dp,
                filled = true,
                contentDescription = "Играть/пауза",
                onClick = viewModel::toggle,
            )
            TransportBlock(
                icon = Icons.Rounded.SkipNext,
                size = 56.dp,
                contentDescription = "Следующий",
                onClick = {
                    if (queue.upcoming.isNotEmpty()) {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    } else {
                        viewModel.skipNext()
                    }
                },
            )
            TransportBlock(
                icon = Icons.Outlined.Repeat,
                size = 44.dp,
                active = isRepeatOn,
                contentDescription = "Зациклить",
                onClick = { isRepeatOn = !isRepeatOn },
            )
        }
        // Format badge sits below the transport controls per Дизайн.md §4.3 (mockup order:
        // controls, then format badge row, then the pill row) -- was above the scrubber before.
        queue.nowPlaying?.format?.let { format ->
            Text(
                text = format.uppercase(),
                color = NamiColors.Ai,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .background(NamiColors.Ai.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // Bottom pill row per Дизайн.md §4.3: Очередь (real), night mode + lyrics ("Текст") are
        // stubs -- neither an AMOLED/night toggle nor a lyrics screen exists yet, so these are
        // present per the mockup but currently no-ops. Очередь/Текст are rectangular with sharp
        // corners (r4); night mode is its own small circle, set apart from the other two.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NowPlayingPill(
                text = "Очередь",
                icon = Icons.Outlined.QueueMusic,
                onClick = onQueueClick,
                modifier = Modifier.weight(1f),
            )
            NowPlayingPill(icon = Icons.Outlined.DarkMode, onClick = {}, shape = CircleShape, modifier = Modifier.size(48.dp))
            NowPlayingPill(text = "Текст", icon = Icons.Outlined.Subject, onClick = {}, modifier = Modifier.weight(1f))
        }
    }
    }
}

@Composable
private fun NowPlayingPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    // Rectangle, but noticeably rounded (not the near-sharp r4 this started at); night mode
    // passes CircleShape to stand apart as its own small round button.
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .background(NamiColors.Ink800, shape)
            .fullBlockClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = text, tint = NamiColors.Paper70, modifier = Modifier.size(18.dp))
            if (text != null) androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 4.dp))
        }
        text?.let { Text(text = it, color = NamiColors.Paper70) }
    }
}

@Composable
private fun TransportBlock(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(size / 3.5f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(color = if (filled) NamiColors.Paper100 else NamiColors.Ink800, shape = shape)
            .fullBlockClickable(shape = shape, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                filled -> NamiColors.Ink900
                active -> NamiColors.Shu
                else -> NamiColors.Paper100
            },
            modifier = Modifier.fillMaxSize().padding(size / 4),
        )
    }
}

// Fires the actual track change once the pager settles on the previous/next page (0/2), then
// snaps it back to the center page (1) with no animation -- page 1 now shows the NEW current
// track, so nothing visibly moves. Landing on 0/2 with nothing to show there (start/end of
// queue) just snaps back without skipping.
@Composable
internal fun LaunchedEffectSettlePage(
    pagerState: PagerState,
    hasPrevious: Boolean,
    hasNext: Boolean,
    viewModel: NowPlayingViewModel,
) {
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when (page) {
                0 -> {
                    if (hasPrevious) viewModel.skipToPreviousTrack()
                    pagerState.scrollToPage(1)
                }
                2 -> {
                    if (hasNext) viewModel.skipNext()
                    pagerState.scrollToPage(1)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingArtwork(artworkPath: String?, contentDescription: String?, modifier: Modifier) {
    if (artworkPath != null) {
        AsyncImage(
            model = artworkPath,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
