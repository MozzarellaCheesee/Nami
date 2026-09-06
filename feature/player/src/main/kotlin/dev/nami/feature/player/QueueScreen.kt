// QueueScreen.kt
package dev.nami.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin

private val QUEUE_ROW_HEIGHT = 64.dp
private val ARTWORK_SIZE = 44.dp
private const val DISMISS_THRESHOLD_DP = 120

// Wrapping each item with its position in the ORIGINAL (source-of-truth) list gives every slot
// a key that's stable and unique even when the same track appears twice in the queue -- no need
// to special-case duplicate track ids. originalIndex is also exactly what moveQueueItem needs to
// commit a drag: "the item that started at originalIndex is now at this position".
private data class SlotItem(val item: QueueItem, val originalIndex: Int)

private fun toSlots(items: List<QueueItem>) = items.mapIndexed { index, item -> SlotItem(item, index) }

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val manual = queue.upcoming.filter { it.origin == QueueOrigin.MANUAL }
    val context = queue.upcoming.filter { it.origin == QueueOrigin.CONTEXT }
    val contextStartIndex = manual.size

    // Reordering is purely local while a drag is in progress -- moveQueueItem goes through
    // ExoPlayer, which doesn't update `queue` synchronously, so firing it on every threshold
    // crossed during the drag left the displayed list lagging behind the assumed state (visible
    // as a gap/drift between the row and the finger). Local list state updates instantly instead;
    // the real move is committed once, when the drag ends.
    var manualOrder by remember(manual) { mutableStateOf(toSlots(manual)) }
    var contextOrder by remember(context) { mutableStateOf(toSlots(context)) }

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // The row actively being drag-reordered skips animateItem() -- its position is already
    // driven live by the local reorder above, and its own placement animation would fight the
    // graphicsLayer offset correction, causing a visible jump/lag under the finger.
    var draggingManualKey by remember { mutableStateOf<Int?>(null) }
    var draggingContextKey by remember { mutableStateOf<Int?>(null) }

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
                itemsIndexed(manualOrder, key = { _, slot -> slot.originalIndex }) { index, slot ->
                    QueueRow(
                        item = slot.item,
                        onDragBy = { relativeMove ->
                            val target = (index + relativeMove).coerceIn(0, manualOrder.lastIndex)
                            if (target != index) {
                                manualOrder = manualOrder.toMutableList().apply { add(target, removeAt(index)) }
                            }
                        },
                        onDraggingChange = { isDragging ->
                            if (isDragging) {
                                draggingManualKey = slot.originalIndex
                            } else {
                                draggingManualKey = null
                                val finalIndex = manualOrder.indexOfFirst { it.originalIndex == slot.originalIndex }
                                if (finalIndex != slot.originalIndex) viewModel.moveQueueItem(slot.originalIndex, finalIndex)
                            }
                        },
                        onRemove = { viewModel.removeQueueItem(index) },
                        modifier = if (draggingManualKey == slot.originalIndex) Modifier else Modifier.animateItem(),
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
                itemsIndexed(contextOrder, key = { _, slot -> slot.originalIndex }) { index, slot ->
                    QueueRow(
                        item = slot.item,
                        onDragBy = { relativeMove ->
                            val target = (index + relativeMove).coerceIn(0, contextOrder.lastIndex)
                            if (target != index) {
                                contextOrder = contextOrder.toMutableList().apply { add(target, removeAt(index)) }
                            }
                        },
                        onDraggingChange = { isDragging ->
                            if (isDragging) {
                                draggingContextKey = slot.originalIndex
                            } else {
                                draggingContextKey = null
                                val finalIndex = contextOrder.indexOfFirst { it.originalIndex == slot.originalIndex }
                                if (finalIndex != slot.originalIndex) {
                                    viewModel.moveQueueItem(contextStartIndex + slot.originalIndex, contextStartIndex + finalIndex)
                                }
                            }
                        },
                        onRemove = { viewModel.removeQueueItem(contextStartIndex + index) },
                        modifier = if (draggingContextKey == slot.originalIndex) Modifier else Modifier.animateItem(),
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

// One row style for both sections -- manually-queued and context (album/playlist) tracks are
// both reorderable and removable the same way. Drag the handle to reorder live (within its own
// section), swipe left to remove -- no separate delete button, swipe is the only way out.
@Composable
private fun QueueRow(
    item: QueueItem,
    onDragBy: (Int) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // dragOffsetPx follows the finger exactly (visual only). firedOffsetPx tracks how much of
    // that has already been converted into list moves, so a move never resets/snaps the visual
    // offset -- it just keeps sliding smoothly under the finger.
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var firedOffsetPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val moveUnitPx = with(density) { (QUEUE_ROW_HEIGHT / 2).toPx() }
    var removed by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                removed = true
                true
            } else {
                false
            }
        },
    )

    AnimatedVisibility(
        visible = !removed,
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        // The vertical drag offset/zIndex live OUTSIDE SwipeToDismissBox entirely -- applying
        // them to content inside the swipe box was letting its dismiss background (the yellow
        // "Kin" wash) show through/get triggered by a purely vertical drag. Keeping the two
        // gestures on separate layers means dragging up/down never touches swipe state.
        Box(
            modifier = Modifier
                // Each fired move actually shifts this row's slot in the list by one row height
                // (via the reorder + animateItem on the other rows), so the leftover visual
                // offset needed is only what hasn't been "spent" on a move yet -- using the raw
                // finger delta here was double-counting the shift, drifting the row far from
                // the finger with every move fired.
                .graphicsLayer { translationY = dragOffsetPx - firedOffsetPx }
                .zIndex(if (dragging) 1f else 0f),
        ) {
            SwipeToDismissBox(
                state = dismissState,
                // Only left (EndToStart, toward removal) is a real gesture here -- without this,
                // swiping right still drags the row (StartToEnd is enabled by default) with nothing
                // behind it and no action tied to it, which just looks like a stray, meaningless drag.
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    // Only shown while actually swiping toward removal -- an icon so the gesture
                    // reads as "this is about to remove the track", not just a flat color wash.
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
                        .background(if (dragging) NamiColors.Ink700 else NamiColors.Ink900)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueTrackInfo(item = item, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(40.dp)
                            .pointerInput(Unit) {
                                // A dedicated handle icon, isolated from the row's own swipe/click
                                // gestures -- no need to wait for a long press before it starts.
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = true
                                        currentOnDraggingChange(true)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetPx += dragAmount.y
                                        // Fire moves live, as the drag crosses each half-row
                                        // threshold, instead of only computing one jump on
                                        // release -- this is what makes other rows actually
                                        // shift out of the way while the drag is in progress.
                                        // The visual offset (dragOffsetPx) is never touched here,
                                        // only how much of it has been "spent" on moves so far --
                                        // the row keeps tracking the finger 1:1.
                                        while (dragOffsetPx - firedOffsetPx >= moveUnitPx) {
                                            firedOffsetPx += moveUnitPx
                                            currentOnDragBy(1)
                                        }
                                        while (dragOffsetPx - firedOffsetPx <= -moveUnitPx) {
                                            firedOffsetPx -= moveUnitPx
                                            currentOnDragBy(-1)
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = false
                                        currentOnDraggingChange(false)
                                        // Collapse to just the unspent residual (display value is
                                        // unchanged by this), then animate that down to 0.
                                        dragOffsetPx -= firedOffsetPx
                                        firedOffsetPx = 0f
                                        scope.launch { animate(dragOffsetPx, 0f) { value, _ -> dragOffsetPx = value } }
                                    },
                                    onDragCancel = {
                                        dragging = false
                                        currentOnDraggingChange(false)
                                        dragOffsetPx -= firedOffsetPx
                                        firedOffsetPx = 0f
                                        scope.launch { animate(dragOffsetPx, 0f) { value, _ -> dragOffsetPx = value } }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.DragHandle,
                            contentDescription = "Перетащить, чтобы изменить порядок",
                            tint = NamiColors.Paper70,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(removed) {
        if (removed) {
            delay(200)
            onRemove()
        }
    }
}
