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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
private const val AUTOSCROLL_EDGE_DP = 72
private const val AUTOSCROLL_SPEED_PX_PER_FRAME = 18f

// The same track can legitimately appear more than once in the queue (added to queue twice,
// present in an album AND queued manually, etc.) -- keying purely by track id then crashes
// LazyColumn with "Key ... was already used" the moment that happens. Only duplicates get a
// disambiguating suffix, so the common (no-duplicate) case keeps a fully stable key -- unlike an
// index-based key, this one stays the same across a reorder (it doesn't depend on position), which
// is what lets animateItem() recognize "this is the same item, now elsewhere" and animate the move
// instead of treating it as unrelated content appearing at an old slot.
private fun dedupedKeys(items: List<QueueItem>, prefix: String): List<String> {
    val seen = mutableMapOf<String, Int>()
    return items.map { item ->
        val id = item.track.id.value
        val occurrence = seen.getOrDefault(id, 0)
        seen[id] = occurrence + 1
        if (occurrence == 0) "$prefix-$id" else "$prefix-$id-$occurrence"
    }
}

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val manual = queue.upcoming.filter { it.origin == QueueOrigin.MANUAL }
    val context = queue.upcoming.filter { it.origin == QueueOrigin.CONTEXT }
    val contextStartIndex = manual.size
    val manualKeys = remember(manual) { dedupedKeys(manual, "manual") }
    val contextKeys = remember(context) { dedupedKeys(context, "context") }

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // List's own bounds in root coordinates -- compared against the dragged row's live finger
    // position to decide when to autoscroll. Captured once on layout, doesn't change during drag.
    var listTop by remember { mutableStateOf(0f) }
    var listBottom by remember { mutableStateOf(0f) }
    val edgePx = with(density) { AUTOSCROLL_EDGE_DP.dp.toPx() }
    // Explicit Job instead of a LaunchedEffect keyed on the pointer position: that position
    // changes on nearly every pixel of movement, so keying on it restarted/raced the effect
    // constantly and could leave a scroll loop running past release. A single job, cancelled and
    // replaced on every update (including the final "stop" on release), is deterministic.
    var autoscrollJob by remember { mutableStateOf<Job?>(null) }
    // Every px the list scrolls while a row is being dragged has to be added back into that row's
    // visual offset -- otherwise the row's own layout slot moves with the scrolled content while
    // its graphicsLayer offset stays fixed to the (now stale) finger delta, and the two fight:
    // the row visibly detaches from the finger the moment autoscroll kicks in.
    var scrollCompensationPx by remember { mutableStateOf(0f) }
    fun updateAutoscroll(pointerY: Float?) {
        autoscrollJob?.cancel()
        autoscrollJob = null
        if (pointerY == null) return
        val delta = when {
            pointerY < listTop + edgePx -> -AUTOSCROLL_SPEED_PX_PER_FRAME
            pointerY > listBottom - edgePx -> AUTOSCROLL_SPEED_PX_PER_FRAME
            else -> 0f
        }
        if (delta == 0f) return
        autoscrollJob = scope.launch {
            while (isActive) {
                val scrolled = listState.scrollBy(delta)
                scrollCompensationPx += scrolled
                delay(16)
            }
        }
    }

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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    listTop = coords.positionInRoot().y
                    listBottom = listTop + coords.size.height
                },
        ) {
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
                itemsIndexed(manual, key = { index, _ -> manualKeys[index] }) { index, item ->
                    QueueRow(
                        item = item,
                        rowHeight = QUEUE_ROW_HEIGHT,
                        scrollCompensationPx = scrollCompensationPx,
                        onDragTo = { relativeMove ->
                            val target = (index + relativeMove).coerceIn(0, manual.lastIndex)
                            if (target != index) viewModel.moveQueueItem(index, target)
                        },
                        onDragPositionChange = { rootY -> updateAutoscroll(rootY) },
                        onRemove = { viewModel.removeQueueItem(index) },
                        modifier = Modifier.animateItem(),
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
                itemsIndexed(context, key = { index, _ -> contextKeys[index] }) { index, item ->
                    QueueRow(
                        item = item,
                        rowHeight = QUEUE_ROW_HEIGHT,
                        scrollCompensationPx = scrollCompensationPx,
                        onDragTo = { relativeMove ->
                            val target = (index + relativeMove).coerceIn(0, context.lastIndex)
                            if (target != index) {
                                viewModel.moveQueueItem(contextStartIndex + index, contextStartIndex + target)
                            }
                        },
                        onDragPositionChange = { rootY -> updateAutoscroll(rootY) },
                        onRemove = { viewModel.removeQueueItem(contextStartIndex + index) },
                        modifier = Modifier.animateItem(),
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
// both reorderable and removable the same way. Drag the handle: the row is pinned to the finger
// 1:1 (no live snapping/reordering of the list while dragging), on release the total move is
// rounded to whole rows and committed once -- swipe left to remove, no separate delete button.
@Composable
private fun QueueRow(
    item: QueueItem,
    rowHeight: androidx.compose.ui.unit.Dp,
    scrollCompensationPx: Float,
    onDragTo: (Int) -> Unit,
    onDragPositionChange: (Float?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragTo by rememberUpdatedState(onDragTo)
    val currentOnDragPositionChange by rememberUpdatedState(onDragPositionChange)
    val currentScrollCompensationPx by rememberUpdatedState(scrollCompensationPx)
    // If autoscroll carries this row far enough, LazyColumn recycles/disposes it like any other
    // item leaving the viewport -- mid-drag, with no chance for onDragEnd/onDragCancel to run.
    // Without this, the autoscroll loop up in QueueScreen never gets its "stop" signal and keeps
    // scrolling forever. onDispose is the one callback guaranteed to fire even when torn down.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { currentOnDragPositionChange(null) }
    }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // scrollCompensationPx at the moment THIS drag started -- only scrolling that happens during
    // this drag should be folded into its offset.
    var scrollCompensationAtStart by remember { mutableStateOf(0f) }
    // Root position of the drag handle at the moment the gesture starts -- combined with the
    // raw accumulated drag delta, gives the finger's absolute Y for the autoscroll check without
    // needing continuous re-measurement while the row's own translationY is animating.
    var handleRootY by remember { mutableStateOf(0f) }
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
        // zIndex has to sit on the item's own root modifier -- LazyColumn only lifts an item
        // above its siblings in the parent's draw order when zIndex is set here, not on some
        // descendant nested further inside the item's content (that only reorders drawing among
        // that item's own children, not the item as a whole against other items in the list).
        modifier = modifier.zIndex(if (dragging) 1f else 0f),
    ) {
        // The vertical drag offset lives OUTSIDE SwipeToDismissBox entirely -- applying it to
        // content inside the swipe box let its dismiss background (the yellow "Kin" wash) get
        // triggered by a purely vertical drag. Keeping the two gestures on separate layers means
        // dragging up/down never touches swipe state.
        Box(
            modifier = Modifier.graphicsLayer {
                translationY = dragOffsetPx + (currentScrollCompensationPx - scrollCompensationAtStart)
            },
        ) {
            SwipeToDismissBox(
                state = dismissState,
                // Only left (EndToStart, toward removal) is a real gesture here -- without this,
                // swiping right still drags the row (StartToEnd is enabled by default) with
                // nothing behind it and no action tied to it.
                enableDismissFromStartToEnd = false,
                backgroundContent = {
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
                        .height(rowHeight)
                        .background(if (dragging) NamiColors.Ink700 else NamiColors.Ink900)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueTrackInfo(item = item, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(40.dp)
                            .onGloballyPositioned { handleRootY = it.positionInRoot().y }
                            .pointerInput(Unit) {
                                // A dedicated handle icon, isolated from the row's own swipe/click
                                // gestures -- no need to wait for a long press before it starts.
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = true
                                        scrollCompensationAtStart = currentScrollCompensationPx
                                        currentOnDragPositionChange(handleRootY)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetPx += dragAmount.y
                                        currentOnDragPositionChange(
                                            handleRootY + dragOffsetPx + (currentScrollCompensationPx - scrollCompensationAtStart),
                                        )
                                    },
                                    onDragEnd = {
                                        dragging = false
                                        currentOnDragPositionChange(null)
                                        // Total displacement relative to the list content: raw
                                        // finger movement plus whatever the list itself scrolled
                                        // underneath the row while autoscrolling near an edge.
                                        val totalOffset = dragOffsetPx + (currentScrollCompensationPx - scrollCompensationAtStart)
                                        val moveBy = (totalOffset / rowHeightPx).roundToInt()
                                        // Snap straight to 0 -- the list reorder (below) lands this
                                        // row's slot exactly where the offset currently puts it, so
                                        // LazyColumn's own item-placement animation carries it the
                                        // rest of the way with nothing left to fight it.
                                        dragOffsetPx = 0f
                                        scrollCompensationAtStart = currentScrollCompensationPx
                                        if (moveBy != 0) currentOnDragTo(moveBy)
                                    },
                                    onDragCancel = {
                                        dragging = false
                                        currentOnDragPositionChange(null)
                                        dragOffsetPx = 0f
                                        scrollCompensationAtStart = currentScrollCompensationPx
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
