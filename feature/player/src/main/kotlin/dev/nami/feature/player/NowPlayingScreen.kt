package dev.nami.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val dragOffsetY = remember { Animatable(0f) }
    val artworkOffsetX = remember { Animatable(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
            .background(NamiColors.Ink900)
            .navigationBarsPadding()
            .pointerInput(Unit) {
                val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val next = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                            dragOffsetY.snapTo(next)
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (dragOffsetY.value > dismissThresholdPx) {
                                onCollapse()
                            } else {
                                dragOffsetY.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { dragOffsetY.animateTo(0f) }
                    },
                )
            }
            .padding(20.dp),
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть", tint = NamiColors.Paper100)
        }
        val artworkModifier = Modifier
            .fillMaxWidth()
            .height(310.dp)
            .padding(vertical = 24.dp)
            .offset { IntOffset(artworkOffsetX.value.roundToInt(), 0) }
            .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                val skipThresholdPx = with(density) { SKIP_THRESHOLD_DP.dp.toPx() }
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { artworkOffsetX.snapTo(artworkOffsetX.value + dragAmount) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val offset = artworkOffsetX.value
                            when {
                                offset < -skipThresholdPx -> viewModel.skipNext()
                                offset > skipThresholdPx -> viewModel.skipPrevious()
                            }
                            artworkOffsetX.animateTo(0f)
                        }
                    },
                    onDragCancel = {
                        scope.launch { artworkOffsetX.animateTo(0f) }
                    },
                )
            }

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
        Text(
            text = queue.nowPlaying?.title ?: "Ничего не играет",
            color = NamiColors.Paper100,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
        queue.nowPlaying?.artistName?.let { artistName ->
            Text(text = artistName, color = NamiColors.Paper70)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = viewModel::skipPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущий", tint = NamiColors.Paper100)
            }
            IconButton(onClick = viewModel::toggle) {
                Icon(
                    imageVector = if (playing?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Играть/пауза",
                    tint = NamiColors.Ink900,
                    modifier = Modifier
                        .background(NamiColors.Paper100, RoundedCornerShape(20.dp))
                        .padding(12.dp),
                )
            }
            IconButton(onClick = viewModel::skipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Следующий", tint = NamiColors.Paper100)
            }
        }
        androidx.compose.material3.TextButton(onClick = onQueueClick, modifier = Modifier.padding(top = 12.dp)) {
            Text(text = "Очередь", color = NamiColors.Paper70)
        }
    }
}
