package dev.nami.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
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
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 120
private const val SKIP_THRESHOLD_DP = 96

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
    val skipThresholdPx = with(density) { SKIP_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var artworkOffsetX by remember { mutableFloatStateOf(0f) }
    var artworkWidthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Shared by the swipe gesture and the chevron button so both dismiss paths always finish
    // the slide-down themselves before popping -- see the comment on the swipe branch below.
    fun collapseAnimated() {
        scope.launch {
            animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
            onCollapse()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .background(NamiColors.Ink900)
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
        IconButton(onClick = ::collapseAnimated) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть", tint = NamiColors.Paper100)
        }
        val artworkModifier = Modifier
            .padding(vertical = 24.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { artworkWidthPx = it.width }
            .offset { IntOffset(artworkOffsetX.roundToInt(), 0) }
            .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> artworkOffsetX += delta },
                onDragStopped = {
                    val exitDistance = artworkWidthPx.toFloat() + skipThresholdPx
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

        Box(contentAlignment = Alignment.Center) {
            // Soft accent glow behind the artwork, per Дизайн.md's "мягкое свечение цветом
            // акцента" -- Compose has no CSS box-shadow, so a blurred radial gradient sitting
            // behind the artwork approximates it (Modifier.blur needs API 31+; on older devices
            // it degrades to an unblurred soft-edged gradient, still reading as a glow).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(vertical = 24.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(NamiColors.Shu.copy(alpha = 0.35f), NamiColors.Shu.copy(alpha = 0f)),
                        ),
                    )
                    .blur(32.dp),
            )
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
        }
        Text(
            text = queue.nowPlaying?.title ?: "Ничего не играет",
            color = NamiColors.Paper100,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
        )
        queue.nowPlaying?.artistName?.let { artistName ->
            Text(text = artistName, color = NamiColors.Paper70)
        }
        queue.nowPlaying?.format?.let { format ->
            Text(
                text = format.uppercase(),
                color = NamiColors.Ai,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .background(NamiColors.Ai.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
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
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = viewModel::skipPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущий", tint = NamiColors.Paper100)
            }
            IconButton(
                onClick = viewModel::toggle,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = if (playing?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Играть/пауза",
                    tint = NamiColors.Ink900,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NamiColors.Paper100, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                )
            }
            IconButton(onClick = viewModel::skipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Следующий", tint = NamiColors.Paper100)
            }
        }
        androidx.compose.material3.TextButton(
            onClick = onQueueClick,
            modifier = Modifier
                .padding(top = 20.dp)
                .height(44.dp)
                .background(NamiColors.Ink800, RoundedCornerShape(22.dp)),
        ) {
            Text(text = "Очередь", color = NamiColors.Paper70)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
