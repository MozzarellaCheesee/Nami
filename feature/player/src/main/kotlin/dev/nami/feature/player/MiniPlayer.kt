package dev.nami.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt

private const val SKIP_THRESHOLD_DP = 80
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
    val playing = state as? PlaybackState.Playing
    val density = LocalDensity.current
    val skipThresholdPx = with(density) { SKIP_THRESHOLD_DP.dp.toPx() }
    val expandThresholdPx = with(density) { EXPAND_THRESHOLD_DP.dp.toPx() }
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var artworkOffsetX by remember { mutableFloatStateOf(0f) }
    var blockWidthPx by remember { mutableIntStateOf(0) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    if (queue.nowPlaying == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(NamiColors.Ink800)
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
                            // offset above) before stopping playback -- stopping clears
                            // queue.nowPlaying, which makes this composable disappear, so the
                            // stop has to happen only once it's already off the visible area.
                            animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
                            viewModel.stop()
                        }
                        else -> animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                    }
                },
            )
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> artworkOffsetX += delta },
                onDragStopped = {
                    val exitDistance = blockWidthPx.toFloat() + skipThresholdPx
                    when {
                        artworkOffsetX < -skipThresholdPx -> {
                            animate(artworkOffsetX, -exitDistance) { value, _ -> artworkOffsetX = value }
                            viewModel.skipNext()
                            artworkOffsetX = exitDistance
                            animate(artworkOffsetX, 0f) { value, _ -> artworkOffsetX = value }
                        }
                        artworkOffsetX > skipThresholdPx -> {
                            animate(artworkOffsetX, exitDistance) { value, _ -> artworkOffsetX = value }
                            viewModel.skipPrevious()
                            artworkOffsetX = -exitDistance
                            animate(artworkOffsetX, 0f) { value, _ -> artworkOffsetX = value }
                        }
                        else -> animate(artworkOffsetX, 0f) { value, _ -> artworkOffsetX = value }
                    }
                },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork + title slide together as one block on swipe; clip so the block doesn't
        // visibly spill under the play/skip buttons while off to one side mid-drag.
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { blockWidthPx = it.width }
                .offset { IntOffset(artworkOffsetX.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artworkModifier = Modifier
                .size(ARTWORK_SIZE_DP.dp)
                .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
            if (queue.nowPlaying?.artworkPath != null) {
                AsyncImage(
                    model = queue.nowPlaying?.artworkPath,
                    contentDescription = queue.nowPlaying?.title,
                    contentScale = ContentScale.Crop,
                    modifier = artworkModifier,
                )
            } else {
                Box(modifier = artworkModifier)
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = queue.nowPlaying?.title ?: "Ничего не играет",
                    color = NamiColors.Paper100,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
                queue.nowPlaying?.artistName?.let { artistName ->
                    Text(
                        text = artistName,
                        color = NamiColors.Paper70,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
        IconButton(onClick = viewModel::toggle) {
            Icon(
                imageVector = if (playing?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing?.isPlaying == true) "Пауза" else "Играть",
                tint = NamiColors.Paper100,
            )
        }
    }
}
