// QueueScreen.kt
package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin

private val QUEUE_ROW_HEIGHT = 56.dp

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val manual = queue.upcoming.filter { it.origin == QueueOrigin.MANUAL }
    val context = queue.upcoming.filter { it.origin == QueueOrigin.CONTEXT }
    val contextStartIndex = manual.size

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink800)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = "Очередь", color = NamiColors.Paper100)
        }
        queue.nowPlaying?.let { current ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(text = current.title, color = NamiColors.Shu)
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
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
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
        ) {
            Text(text = "≡", color = NamiColors.Paper40)
        }
        Text(text = item.track.title, color = NamiColors.Paper100, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Удалить", tint = NamiColors.Paper70)
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
        backgroundContent = { Box(modifier = Modifier.fillMaxSize().background(NamiColors.Kin)) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(QUEUE_ROW_HEIGHT)
                .background(NamiColors.Ink800)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = item.track.title, color = NamiColors.Paper100)
        }
    }
}
