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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import coil3.request.crossfade
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.request.ImageRequest
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.fullBlockClickable
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 120

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onCollapse: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by viewModel.playbackState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val trackDetails by viewModel.currentTrackDetails.collectAsState()
    val playing = state as? PlaybackState.Playing
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAudioTractSheet by remember { mutableStateOf(false) }
    var showEqualizerInSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    // Local-only stub -- no "favorites" concept exists in the domain layer yet, so this doesn't
    // persist across tracks/sessions. Resets whenever the playing track changes.
    // Real, not a stub: backed by the Любимые треки system playlist (PlaylistRepository.
    // isTrackLiked/toggleLike) -- see LikedPlaylistCover for the playlist's own heart cover.
    val isFavorite by viewModel.isCurrentTrackLiked.collectAsState()
    // Real, not a stub: viewModel.shuffleEnabled reflects the live queue's actual order (see
    // PlayerRepository.setShuffleEnabled) -- toggling this really reorders/restores the queue.
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    // Real ExoPlayer repeat mode -- OFF/ALL/ONE, cycled by cycleRepeatMode().
    val repeatMode by viewModel.repeatMode.collectAsState()
    // Real, persisted -- see SettingsRepository.nightModeEnabled. Read up here (not just at the
    // pill row further down) so the ambient backdrop below can react to it too.
    val nightModeEnabled by viewModel.nightModeEnabled.collectAsState()

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
            // Coil's own crossfade (not an abrupt swap) between the old and new backdrop -- Coil
            // caches the previous successful result for this ImageView-equivalent internally, so
            // this alone is enough, no separate AnimatedContent/Crossfade wrapper needed.
            val request = ImageRequest.Builder(LocalPlatformContext.current)
                .data(backgroundArtworkPath)
                .crossfade(400)
                .build()
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(64.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900.copy(alpha = if (backgroundArtworkPath != null) 0.72f else 1f)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding().displayCutoutPadding()
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
        if (showOverflowMenu) {
            ContextActionSheet(
                onDismiss = { showOverflowMenu = false },
                actions = listOf(
                    ContextAction("Аудиотракт", Icons.Outlined.QueueMusic) { showAudioTractSheet = true },
                    ContextAction("Таймер сна", Icons.Outlined.DarkMode) { showSleepTimerSheet = true },
                ),
            )
        }
        // Аудиотракт (and, from inside it, Эквалайзер) appear right here as a sliding-up sheet
        // instead of navigating to a separate screen -- same content (AudioTractBody/
        // EqualizerBody) the standalone routes use, just embedded. showEqualizerInSheet swaps
        // which body the ONE sheet shows instead of stacking a second ModalBottomSheet on top.
        if (showAudioTractSheet) {
            ModalBottomSheet(onDismissRequest = { showAudioTractSheet = false; showEqualizerInSheet = false }) {
                dev.nami.core.designsystem.ImmersiveSheetEffect()
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    if (showEqualizerInSheet) {
                        EqualizerBody(onBack = { showEqualizerInSheet = false })
                    } else {
                        AudioTractBody(onOpenEqualizer = { showEqualizerInSheet = true })
                    }
                }
            }
        }
        if (showSleepTimerSheet) {
            val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsState()
            SleepTimerSheet(
                remainingMs = sleepTimerRemainingMs,
                onDismiss = { showSleepTimerSheet = false },
                onStart = { minutes -> viewModel.startSleepTimer(minutes * 60_000L) },
                onCancel = { viewModel.cancelSleepTimer() },
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
        val autoAdvanceSignal by viewModel.autoAdvanceSignal.collectAsState()
        val suppressSkip = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        LaunchedEffectSettlePage(pagerState, queue.previousTrack != null, queue.upcoming.isNotEmpty(), viewModel, suppressSkip = { suppressSkip.value })
        LaunchedEffectAutoAdvance(pagerState, autoAdvanceSignal, queue.previousTrack != null, suppressSkip)

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
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(32.dp).fullBlockClickable(shape = CircleShape) { viewModel.toggleLikeCurrentTrack() },
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
        val waveform by viewModel.waveform.collectAsState()
        val moments by viewModel.currentTrackMoments.collectAsState()
        var pendingMomentFraction by remember { mutableStateOf<Float?>(null) }
        WaveformScrubber(
            seedKey = queue.nowPlaying?.id?.value ?: "",
            progress = actualProgress,
            onSeek = { fraction -> viewModel.seek((fraction * durationMs).toLong()) },
            onProgressPreview = { fraction -> previewProgress = fraction },
            onPreviewEnd = { previewProgress = null },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            realHeights = waveform,
            moments = if (durationMs > 0) moments.map { (it.positionMs.toFloat() / durationMs) to it.colorArgb } else emptyList(),
            onLongPress = { fraction -> pendingMomentFraction = fraction },
        )
        pendingMomentFraction?.let { fraction ->
            AddMomentDialog(
                onSave = { label, colorArgb ->
                    viewModel.addMoment((fraction * durationMs).toLong(), label, colorArgb)
                    pendingMomentFraction = null
                },
                onDismiss = { pendingMomentFraction = null },
            )
        }
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
        // > shuffle/repeat (44). Shuffle is real (see shuffleEnabled above); repeat is still a
        // visual-only stub -- no loop behavior wired yet, a separate task.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportBlock(
                icon = Icons.Outlined.Shuffle,
                size = 44.dp,
                active = shuffleEnabled,
                contentDescription = "Перемешать",
                onClick = { viewModel.toggleShuffle() },
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
                        scope.launch { pagerState.animateScrollToPage(0, animationSpec = TRACK_SLIDE_SPEC) }
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
                        scope.launch { pagerState.animateScrollToPage(2, animationSpec = TRACK_SLIDE_SPEC) }
                    } else {
                        viewModel.skipNext()
                    }
                },
            )
            TransportBlock(
                icon = Icons.Outlined.Repeat,
                size = 44.dp,
                active = repeatMode != dev.nami.domain.RepeatMode.OFF,
                badgeText = if (repeatMode == dev.nami.domain.RepeatMode.ONE) "1" else null,
                contentDescription = when (repeatMode) {
                    dev.nami.domain.RepeatMode.OFF -> "Зациклить очередь"
                    dev.nami.domain.RepeatMode.ALL -> "Зациклить один трек"
                    dev.nami.domain.RepeatMode.ONE -> "Выключить цикл"
                },
                onClick = { viewModel.cycleRepeatMode() },
            )
        }
        // Format badge sits below the transport controls per Дизайн.md §4.3 (mockup order:
        // controls, then format badge row, then the pill row) -- was above the scrubber before.
        // Detail string (bitrate/sample-rate-bit-depth/size) needs the full Track (byte size,
        // duration), not just QueueTrack's format string -- falls back to just the format badge
        // until currentTrackDetails' lookup resolves, and stays format-only if it never does.
        queue.nowPlaying?.format?.let { format ->
            Text(
                text = formatBadgeDetail(format, trackDetails),
                color = NamiColors.Ai,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .background(NamiColors.Ai.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // Bottom pill row per Дизайн.md §4.3: Очередь, night mode, and lyrics ("Текст") --
        // night mode is real (see NowPlayingViewModel.nightModeEnabled/toggleNightMode), not a
        // stub. Очередь/Текст are rectangular with sharp corners (r4); night mode is its own
        // small circle, set apart from the other two.
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
            NowPlayingPill(
                icon = Icons.Outlined.DarkMode,
                onClick = { viewModel.toggleNightMode() },
                shape = CircleShape,
                active = nightModeEnabled,
                modifier = Modifier.size(48.dp),
            )
            NowPlayingPill(text = "Текст", icon = Icons.Outlined.Subject, onClick = onLyricsClick, modifier = Modifier.weight(1f))
        }
    }
    // Real night-mode effect, drawn LAST so it dims everything -- cover art, transport controls,
    // text -- not just the ambient backdrop peeking around the edges (that was the previous,
    // barely-visible version: a scrim placed under the foreground content only tinted what showed
    // through the gaps). No pointerInput/clickable here, so touches still pass straight through
    // to the buttons underneath.
    if (nightModeEnabled) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFF8A00).copy(alpha = 0.10f)))
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
    active: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .background(if (active) NamiColors.Shu.copy(alpha = 0.18f) else NamiColors.Ink800, shape)
            .fullBlockClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = text, tint = if (active) NamiColors.Shu else NamiColors.Paper70, modifier = Modifier.size(18.dp))
            if (text != null) androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 4.dp))
        }
        text?.let { Text(text = it, color = if (active) NamiColors.Shu else NamiColors.Paper70) }
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
    // "1" for repeat-one -- sits inside the Repeat icon's own loop (dead center, same spot the
    // real RepeatOne glyph draws its digit) instead of a separate corner badge, reusing the plain
    // Repeat icon rather than pulling in material-icons-extended for a glyph nothing else needs.
    badgeText: String? = null,
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
        if (badgeText != null) {
            androidx.compose.material3.Text(
                text = badgeText,
                color = if (filled) NamiColors.Ink900 else if (active) NamiColors.Shu else NamiColors.Paper100,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(size.value / 4.2f, androidx.compose.ui.unit.TextUnitType.Sp),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
            )
        }
    }
}

// A slower, explicitly-eased spec for every programmatic page transition (skip buttons, the
// auto-advance replay below) -- the default animateScrollToPage spec reads as an abrupt snap at
// this page size, distinct from the naturally-smooth motion a real finger drag already gets from
// the pager's own fling physics.
internal val TRACK_SLIDE_SPEC = tween<Float>(durationMillis = 420, easing = androidx.compose.animation.core.FastOutSlowInEasing)

// Fires the actual track change once the pager settles on the previous/next page (0/2), then
// snaps it back to the center page (1) with no animation -- page 1 now shows the NEW current
// track, so nothing visibly moves. Landing on 0/2 with nothing to show there (start/end of
// queue) just snaps back without skipping. suppressSkip is true while
// LaunchedEffectAutoAdvance below is doing its own page-0-to-1 replay for a track that ALREADY
// changed on its own -- that replay's own scrollToPage(0) would otherwise be misread as a manual
// swipe-to-previous and trigger a real (wrong, double) skip.
@Composable
internal fun LaunchedEffectSettlePage(
    pagerState: PagerState,
    hasPrevious: Boolean,
    hasNext: Boolean,
    viewModel: NowPlayingViewModel,
    suppressSkip: () -> Boolean = { false },
) {
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when (page) {
                0 -> {
                    if (hasPrevious && !suppressSkip()) viewModel.skipToPreviousTrack()
                    pagerState.scrollToPage(1)
                }
                2 -> {
                    if (hasNext && !suppressSkip()) viewModel.skipNext()
                    pagerState.scrollToPage(1)
                }
            }
        }
    }
}

/** Replays the same slide the pager plays for a manual swipe/skip, but for a track that just
 * ended and auto-advanced on its own -- otherwise the cover just silently jumps to the next
 * track with no motion at all. By the time this fires, queue.previousTrack/nowPlaying already
 * hold the right data for the "just finished" and "now playing" tracks (the transition already
 * happened for real) -- page 0 already shows exactly what page 1 used to show, so jumping there
 * instantly and animating back to 1 IS the transition, no second real skip involved. */
@Composable
internal fun LaunchedEffectAutoAdvance(
    pagerState: PagerState,
    autoAdvanceSignal: Int,
    hasPrevious: Boolean,
    suppressSkip: androidx.compose.runtime.MutableState<Boolean>,
) {
    var seenInitial by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(autoAdvanceSignal) {
        if (!seenInitial) {
            // Skip the value this StateFlow starts with -- only react to it actually changing.
            seenInitial = true
            return@LaunchedEffect
        }
        if (!hasPrevious) return@LaunchedEffect
        // try/finally: without it, a SECOND auto-advance signal arriving before this animation
        // finishes cancels this coroutine mid-flight (LaunchedEffect restarts on a new key) --
        // execution stops right there, skipping the reset below and leaving suppressSkip stuck
        // true FOREVER. That silently broke the real previous-track button/swipe (LaunchedEffect-
        // SettlePage's page-0 branch checks suppressSkip() too) for the rest of the session.
        suppressSkip.value = true
        try {
            pagerState.scrollToPage(0)
            pagerState.animateScrollToPage(1, animationSpec = TRACK_SLIDE_SPEC)
        } finally {
            suppressSkip.value = false
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

/** "FLAC · 24 бит · 96 кГц · 1411 кбит/с · 42.3 МБ" -- as much as we actually know about the
 * file, not guessed. Bitrate is average (fileSize*8/duration), same approximation любой player
 * uses for a non-VBR-analyzed file; container overhead makes it a slight overestimate, close
 * enough to be useful. */
private fun formatBadgeDetail(format: String, track: dev.nami.core.model.Track?): String {
    val parts = buildList {
        add(format.uppercase())
        track?.bitDepth?.let { add("$it бит") }
        track?.sampleRateHz?.let { add("${it / 1000} кГц") }
        if (track != null && track.durationMs > 0) {
            val kbps = (track.sizeBytes * 8) / track.durationMs
            add("$kbps кбит/с")
        }
        track?.sizeBytes?.let { bytes ->
            val mb = bytes / 1024.0 / 1024.0
            add("%.1f МБ".format(mb))
        }
    }
    return parts.joinToString(" · ")
}

/** Long-press on the scrubber (План.md §22.1) -- name it, pick a color, done. Colors are fixed
 * swatches rather than a full picker: a moment marker is a tiny dot on the waveform, a handful of
 * clearly distinct hues reads better there than any color a full picker could produce. */
@Composable
private fun AddMomentDialog(onSave: (label: String, colorArgb: Int) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    val swatches = listOf(
        NamiColors.Shu.toArgb(),
        NamiColors.Ai.toArgb(),
        androidx.compose.ui.graphics.Color(0xFF4CAF50).toArgb(),
        androidx.compose.ui.graphics.Color(0xFFFFC107).toArgb(),
        NamiColors.Paper100.toArgb(),
    )
    var selectedColor by remember { mutableStateOf(swatches.first()) }

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Новая метка", color = NamiColors.Paper100) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { androidx.compose.material3.Text("Например: лучший дроп") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    swatches.forEach { colorArgb ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(androidx.compose.ui.graphics.Color(colorArgb), androidx.compose.foundation.shape.CircleShape)
                                .then(
                                    if (colorArgb == selectedColor) {
                                        Modifier.border(2.dp, NamiColors.Paper100, androidx.compose.foundation.shape.CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { selectedColor = colorArgb },
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(label.ifBlank { "Момент" }, selectedColor) },
            ) { androidx.compose.material3.Text("Добавить", color = NamiColors.Shu) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Отмена", color = NamiColors.Paper70) }
        },
    )
}
