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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt

private const val SKIP_THRESHOLD_DP = 80

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
    var offsetX by remember { mutableFloatStateOf(0f) }

    if (queue.nowPlaying == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(NamiColors.Ink800)
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .clickable(onClick = onExpand)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> offsetX += delta },
                onDragStopped = {
                    when {
                        offsetX < -skipThresholdPx -> viewModel.skipNext()
                        offsetX > skipThresholdPx -> viewModel.skipPrevious()
                    }
                    animate(offsetX, 0f) { value, _ -> offsetX = value }
                },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (queue.nowPlaying?.artworkPath != null) {
            AsyncImage(
                model = queue.nowPlaying?.artworkPath,
                contentDescription = queue.nowPlaying?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = queue.nowPlaying?.title ?: "Ничего не играет",
            color = NamiColors.Paper100,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
                .basicMarquee(),
        )
        IconButton(onClick = viewModel::toggle) {
            Icon(
                imageVector = if (playing?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing?.isPlaying == true) "Пауза" else "Играть",
                tint = NamiColors.Paper100,
            )
        }
        IconButton(onClick = viewModel::skipNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Следующий", tint = NamiColors.Paper100)
        }
    }
}
