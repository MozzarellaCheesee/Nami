// QueueScreen.kt
package dev.nami.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin

private val QUEUE_ROW_HEIGHT = 64.dp
private val ARTWORK_SIZE = 44.dp
private const val DISMISS_THRESHOLD_DP = 120

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val manual = queue.upcoming.filter { it.origin == QueueOrigin.MANUAL }
    val context = queue.upcoming.filter { it.origin == QueueOrigin.CONTEXT }
    val contextStartIndex = manual.size

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .background(NamiColors.Ink900)
            .statusBarsPadding()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                },
                onDragStopped = { velocity ->
                    if (dragOffsetY > dismissThresholdPx || velocity > 2000f) {
                        animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
                        onBack()
                    } else {
                        animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                    }
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = "Очередь", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
        }
        queue.nowPlaying?.let { current ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(text = "Сейчас играет", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
                Text(text = current.title, color = NamiColors.Shu, maxLines = 1)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (manual.isEmpty() && context.isEmpty()) {
                item {
                    Text(
                        text = "Очередь пуста",
                        color = NamiColors.Paper40,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            if (manual.isNotEmpty()) {
                item {
                    Text(
                        text = "Поставлено вручную",
                        color = NamiColors.Paper40,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(manual, key = { index, item -> "manual-$index-${item.track.id.value}" }) { index, item ->
                    ManualQueueRow(
                        item = item,
                        onDragBy = { relativeMove ->
                            val target = (index + relativeMove).coerceIn(0, manual.lastIndex)
                            if (target != index) viewModel.moveQueueItem(index, target)
                        },
                        onRemove = { viewModel.removeQueueItem(index) },
                    )
                }
            }
            if (context.isNotEmpty()) {
                item {
                    Text(
                        text = "Дальше из контекста",
                        color = NamiColors.Paper40,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(context, key = { index, item -> "context-$index-${item.track.id.value}" }) { index, item ->
                    ContextQueueRow(
                        item = item,
                        onRemove = { viewModel.removeQueueItem(contextStartIndex + index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTrackInfo(item: QueueItem, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val artworkModifier = Modifier
            .size(ARTWORK_SIZE)
            .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
        if (item.track.artworkPath != null) {
            AsyncImage(
                model = item.track.artworkPath,
                contentDescription = item.track.title,
                contentScale = ContentScale.Crop,
                modifier = artworkModifier,
            )
        } else {
            Box(modifier = artworkModifier)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(text = item.track.title, color = NamiColors.Paper100, maxLines = 1)
            item.track.artistName?.let { artistName ->
                Text(text = artistName, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ManualQueueRow(item: QueueItem, onDragBy: (Int) -> Unit, onRemove: () -> Unit) {
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val density = LocalDensity.current
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val rowHeightPx = with(density) { QUEUE_ROW_HEIGHT.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QUEUE_ROW_HEIGHT)
            .graphicsLayer { translationY = dragOffsetPx }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueTrackInfo(item = item, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(40.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx += dragAmount.y
                        },
                        onDragEnd = {
                            val moveBy = (dragOffsetPx / rowHeightPx).toInt()
                            dragOffsetPx = 0f
                            if (moveBy != 0) currentOnDragBy(moveBy)
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DragHandle, contentDescription = "Перетащить", tint = NamiColors.Paper40)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = "Удалить", tint = NamiColors.Paper70)
        }
    }
}

@Composable
private fun ContextQueueRow(item: QueueItem, onRemove: () -> Unit) {
    val currentOnRemove by rememberUpdatedState(onRemove)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnRemove()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Only shown while actually swiping toward removal -- progress-scaled icon so the
            // gesture reads as "this is about to remove the track", not just a flat color wash.
            Box(
                modifier = Modifier.fillMaxSize().background(NamiColors.Kin),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Убрать из очереди",
                    tint = NamiColors.Ink900,
                    modifier = Modifier.padding(end = 24.dp).size(28.dp),
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(QUEUE_ROW_HEIGHT)
                .background(NamiColors.Ink900)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueTrackInfo(item = item)
        }
    }
}
