package dev.nami.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.fullBlockClickable
import dev.nami.domain.PlaybackState
import dev.nami.domain.QueueTrack
import kotlin.math.roundToInt

private const val ARTWORK_SIZE_DP = 40
private const val EXPAND_THRESHOLD_DP = 24
private const val DISMISS_THRESHOLD_DP = 40

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by viewModel.playbackState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val blindMode by viewModel.blindModeActive.collectAsState()
    val playing = state as? PlaybackState.Playing
    val density = LocalDensity.current
    val expandThresholdPx = with(density) { EXPAND_THRESHOLD_DP.dp.toPx() }
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // This composable never leaves composition when nowPlaying goes null (the early return
    // below just skips rendering that frame), so a drag offset from a previous dismiss would
    // otherwise stick around and render the NEXT track's bar already shifted off-screen, looking
    // "stuck" and unresponsive. Reset whenever the playing track identity changes.
    androidx.compose.runtime.LaunchedEffect(queue.nowPlaying?.id) { dragOffsetY = 0f }

    // 3-page window: 0 = previous, 1 = current, 2 = next - see the matching comment in
    // NowPlayingScreen for why this replaced a hand-rolled offset carousel.
    val pagerState = rememberPagerState(initialPage = 1) { 3 }
    val autoAdvanceSignal by viewModel.autoAdvanceSignal.collectAsState()
    val suppressSkip = remember { mutableStateOf(false) }
    LaunchedEffectSettlePage(pagerState, queue.previousTrack != null, queue.upcoming.isNotEmpty(), viewModel, suppressSkip = { suppressSkip.value })
    LaunchedEffectAutoAdvance(pagerState, autoAdvanceSignal, queue.previousTrack != null, suppressSkip)

    if (queue.nowPlaying == null) return

    // The bar's own reserved height shrinks in lockstep with the downward drag (instead of
    // just visually sliding via offset while the Column keeps reserving a full 60dp slot for
    // it) so the bottom nav bar rises to close the gap in real time - no leftover strip of
    // background color where the bar used to be, and nothing else visible "flying" through it.
    val fullHeightPx = with(density) { 60.dp.toPx() }
    val reservedHeightPx = (fullHeightPx - dragOffsetY.coerceAtLeast(0f)).coerceIn(0f, fullHeightPx)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { reservedHeightPx.toDp() })
            .clipToBounds(),
    ) {
    Box(modifier = Modifier.fillMaxWidth().height(60.dp).clipToBounds()) {
        // Same ambient-blur idea as Now Playing, scaled down: the current track's own artwork,
        // blurred and dimmed, instead of a flat Ink800 bar.
        val backgroundArtworkPath = if (blindMode) null else queue.nowPlaying?.artworkPath
        if (backgroundArtworkPath != null) {
            AsyncImage(
                model = backgroundArtworkPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(60.dp).blur(24.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(NamiColors.Ink800.copy(alpha = if (backgroundArtworkPath != null) 0.72f else 1f)),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .offset { IntOffset(0, dragOffsetY.coerceAtLeast(0f).roundToInt()) }
            .clickable(onClick = onExpand)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragOffsetY += delta },
                onDragStopped = { velocity ->
                    when {
                        dragOffsetY < -expandThresholdPx || velocity < -2000f -> {
                            dragOffsetY = 0f
                            onExpand()
                        }
                        dragOffsetY > dismissThresholdPx || velocity > 2000f -> {
                            // Slide fully off-screen (finger-tracked while dragging, via the
                            // offset above) before stopping playback - stopping clears
                            // queue.nowPlaying, which makes this composable disappear, so the
                            // stop has to happen only once it's already off the visible area.
                            animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
                            viewModel.stop()
                        }
                        else -> animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                    }
                },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 0 = previous, 1 = current, 2 = next - HorizontalPager owns its own horizontal drag
        // here, so it lives inside the Row without conflicting with the Row's own vertical one.
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f).clipToBounds(),
        ) { page ->
            val track = when (page) {
                0 -> queue.previousTrack
                2 -> queue.upcoming.firstOrNull()?.track
                else -> queue.nowPlaying
            }
            // Слепое прослушивание (группа D) - иначе MiniPlayer сразу палит то, что экран
            // BlindListenScreen специально прячет.
            val masked = if (blindMode && track != null) track.copy(title = "???", artistName = null, artworkPath = null) else track
            MiniPlayerTrackBlock(track = masked)
        }
        val isFavorite by viewModel.isCurrentTrackLiked.collectAsState()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .fullBlockClickable(shape = androidx.compose.foundation.shape.CircleShape, onClick = viewModel::toggleLikeCurrentTrack),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) "Убрать из любимых" else "В любимые",
                tint = if (isFavorite) NamiColors.Shu else NamiColors.Paper70,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .fullBlockClickable(shape = androidx.compose.foundation.shape.CircleShape, onClick = viewModel::toggle),
        ) {
            Icon(
                imageVector = if (playing?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing?.isPlaying == true) "Пауза" else "Играть",
                tint = NamiColors.Paper100,
            )
        }
    }
    }
}

@Composable
private fun MiniPlayerTrackBlock(track: QueueTrack?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val artworkModifier = Modifier
            .size(ARTWORK_SIZE_DP.dp)
            .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
        if (track?.artworkPath != null) {
            AsyncImage(
                model = track.artworkPath,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = artworkModifier,
            )
        } else {
            Box(modifier = artworkModifier)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = track?.title ?: "Ничего не играет",
                color = NamiColors.Paper100,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            )
            track?.artistName?.let { artistName ->
                Text(
                    text = artistName,
                    color = NamiColors.Paper70,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}
