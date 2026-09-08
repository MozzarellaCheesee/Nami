// QueueScreen.kt
package dev.nami.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val QUEUE_ROW_HEIGHT = 64.dp
private val ARTWORK_SIZE = 44.dp
private const val DISMISS_THRESHOLD_DP = 120
private const val AUTOSCROLL_EDGE_DP = 88
private const val AUTOSCROLL_MAX_PX_PER_FRAME = 16f
// Width of the right-hand strip that owns the reorder gesture (drag handle + a little slack).
private const val DRAG_HANDLE_ZONE_DP = 68

private enum class Section { MANUAL, CONTEXT }

/** Everything the drag machinery needs about one row, resolved from a LazyColumn item key. */
private data class RowRef(
    val section: Section,
    val upcomingIndex: Int,
    val item: QueueItem,
)

/**
 * Owned by [QueueScreen], not by any row. Nothing here depends on the dragged row still being
 * composed: the row can be recycled by LazyColumn mid-gesture (autoscroll pushes it out of the
 * viewport) and the drag keeps working, because the gesture lives on the list's parent Box and
 * the visual lives in an overlay next to the list.
 */
private data class DragState(
    val key: String,
    val ref: RowRef,
    val heightPx: Int,
    /** Distance from the row's top edge to the finger, fixed for the whole gesture. */
    val grabOffsetPx: Float,
)

// The same track can legitimately appear more than once in the queue - keying purely by track id
// then crashes LazyColumn with "Key ... was already used". Only duplicates get a disambiguating
// suffix, so the common case keeps a fully stable, position-independent key, which is what lets
// animateItem() recognise "same item, now elsewhere" and animate the move.
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
    // withIndex() keeps each row's real index into `upcoming`, so move/remove stay correct even if
    // manual and context items ever interleave - no "manual.size" offset arithmetic to get wrong.
    val manual = queue.upcoming.withIndex().filter { it.value.origin == QueueOrigin.MANUAL }
    val context = queue.upcoming.withIndex().filter { it.value.origin == QueueOrigin.CONTEXT }
    val manualKeys = dedupedKeys(manual.map { it.value }, "manual")
    val contextKeys = dedupedKeys(context.map { it.value }, "context")
    val refs = buildMap {
        manual.forEachIndexed { i, v -> put(manualKeys[i], RowRef(Section.MANUAL, v.index, v.value)) }
        context.forEachIndexed { i, v -> put(contextKeys[i], RowRef(Section.CONTEXT, v.index, v.value)) }
    }
    // The gesture handler below is created once (pointerInput(Unit)) and must never capture a stale
    // queue; it reads through this State instead.
    val refsState = rememberUpdatedState(refs)

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val edgePx = with(density) { AUTOSCROLL_EDGE_DP.dp.toPx() }
    val handleZonePx = with(density) { DRAG_HANDLE_ZONE_DP.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }

    val listState = rememberLazyListState()
    // Split deliberately: `dragState` changes twice per gesture (start/end) and drives composition;
    // `pointerY` changes on every touch sample and is only read from the layout phase (offset{}) and
    // from the autoscroll coroutine, so following the finger costs no recomposition at all.
    val dragState = remember { mutableStateOf<DragState?>(null) }
    val pointerY = remember { mutableFloatStateOf(0f) }
    val drag = dragState.value

    // Runs strictly while a drag is active: keyed on the boolean, so release (dragState = null)
    // cancels it, and it cannot outlive the gesture or be killed by a row being recycled.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        while (isActive) {
            val viewport = listState.layoutInfo.viewportEndOffset.toFloat()
            val y = pointerY.floatValue
            val speed = when {
                viewport <= 0f -> 0f
                y < edgePx -> -AUTOSCROLL_MAX_PX_PER_FRAME * ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                y > viewport - edgePx ->
                    AUTOSCROLL_MAX_PX_PER_FRAME * ((y - (viewport - edgePx)) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) listState.scrollBy(speed)
            delay(16)
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Swipe-to-dismiss used to manually animate dragOffsetY to screenHeightPx and only THEN call
    // onBack() - that coroutine sometimes never reached onBack() at all (composable
    // disposed/recomposed mid-animation cancels it), leaving showQueue stuck true forever: the
    // screen sat fully slid off-screen but never actually closed, so reopening was a silent
    // no-op. onBack() now always fires immediately and synchronously; dragOffsetY resets to 0 in
    // the same breath so only the outer AnimatedVisibility's own exit transition (in
    // NamiNavHost) animates the slide-down, instead of two competing animations.
    fun dismiss() {
        // Not resetting dragOffsetY here - doing so snapped the screen back to the top for one
        // frame (visible as a jump/teleport) before AnimatedVisibility's own exit transition
        // started sliding it back down from 0. Leaving it wherever the swipe left it means the
        // screen is already most of the way off-screen when the exit transition takes over.
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize().offset { IntOffset(0, dragOffsetY.roundToInt()) }) {
        // Same ambient blurred-artwork backdrop as Now Playing/Lyrics - one consistent look for
        // every screen stacked over the player, not a flat Ink900 fill just for this one.
        val backgroundArtworkPath = queue.nowPlaying?.artworkPath
        if (backgroundArtworkPath != null) {
            AsyncImage(
                model = backgroundArtworkPath,
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
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                },
                onDragStopped = { velocity ->
                    if (dragOffsetY > dismissThresholdPx || velocity > 2000f) {
                        dismiss()
                    } else {
                        scope.launch { animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value } }
                    }
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            // Straight to onBack(), not dismiss() - see LyricsScreen's identical header button
            // for why (dismiss()'s slide-then-flip coroutine could leave the screen stuck open).
            IconButton(onClick = { onBack() }) {
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

        // Commits the reorder exactly once, on release. The drop slot is read from the list's own
        // layoutInfo (nearest same-section row to the floating row's centre) instead of from any
        // accumulated pixel bookkeeping - autoscroll therefore needs no compensation term at all.
        fun commitDrop(d: DragState) {
            val map = refsState.value
            val floatCenter = pointerY.floatValue - d.grabOffsetPx + d.heightPx / 2f
            val target = listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info ->
                    val ref = (info.key as? String)?.let(map::get) ?: return@mapNotNull null
                    if (ref.section != d.ref.section) null else info to ref
                }
                .minByOrNull { (info, _) -> abs(info.offset + info.size / 2f - floatCenter) }
                ?.second ?: return
            if (target.upcomingIndex != d.ref.upcomingIndex) {
                viewModel.moveQueueItem(d.ref.upcomingIndex, target.upcomingIndex)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // The reorder gesture lives HERE, on the list's parent, and never on a row:
                    // a row can be disposed by LazyColumn mid-drag, which silently kills any
                    // pointerInput it owns (no onDragEnd, no onDragCancel - the old bug where
                    // autoscroll kept running and the row stuck). This node outlives every row.
                    // It works on the Initial pass so it can claim the gesture before the list's
                    // own scroll and before SwipeToDismissBox, and it only claims after a vertical
                    // slop inside the handle strip - horizontal swipes are never consumed, so
                    // swipe-to-remove (and its yellow background) stay completely untouched by a
                    // vertical drag.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (down.position.x < size.width - handleZonePx) return@awaitEachGesture
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                down.position.y >= it.offset && down.position.y < it.offset + it.size
                            } ?: return@awaitEachGesture
                            val key = info.key as? String ?: return@awaitEachGesture
                            val ref = refsState.value[key] ?: return@awaitEachGesture

                            val pointer: PointerId = down.id
                            var dx = 0f
                            var dy = 0f
                            var start: PointerInputChange? = null
                            while (start == null) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointer }
                                if (change == null || !change.pressed || change.isConsumed) {
                                    return@awaitEachGesture
                                }
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                // Horizontal first -> this is a swipe-to-remove, hands off.
                                if (abs(dx) > viewConfiguration.touchSlop) return@awaitEachGesture
                                if (abs(dy) > viewConfiguration.touchSlop) start = change
                            }

                            start.consume()
                            pointerY.floatValue = start.position.y
                            dragState.value = DragState(
                                key = key,
                                ref = ref,
                                heightPx = info.size,
                                // Anchored to where the row actually is right now, so the row does
                                // not jump on pick-up and tracks the finger 1:1 from then on.
                                grabOffsetPx = start.position.y - info.offset,
                            )
                            var dropped = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointer } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    dropped = true
                                    break
                                }
                                change.consume()
                                pointerY.floatValue = change.position.y
                            }
                            val finished = dragState.value
                            dragState.value = null
                            if (dropped && finished != null) commitDrop(finished)
                        }
                    },
            ) {
                if (manual.isEmpty() && context.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "Очередь пуста",
                            color = NamiColors.Paper40,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                }
                if (manual.isNotEmpty()) {
                    item(key = "header-manual") {
                        Text(
                            text = "Поставлено вручную",
                            color = NamiColors.Paper40,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(manual, key = { index, _ -> manualKeys[index] }) { index, value ->
                        QueueRow(
                            item = value.value,
                            hidden = drag?.key == manualKeys[index],
                            onRemove = { viewModel.removeQueueItem(value.index) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (context.isNotEmpty()) {
                    item(key = "header-context") {
                        Text(
                            text = "Дальше из контекста",
                            color = NamiColors.Paper40,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(context, key = { index, _ -> contextKeys[index] }) { index, value ->
                        QueueRow(
                            item = value.value,
                            hidden = drag?.key == contextKeys[index],
                            onRemove = { viewModel.removeQueueItem(value.index) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            // The dragged row, drawn as a sibling on top of the whole list - not as a LazyColumn
            // item with a zIndex, which is what used to put it under its neighbours and made it
            // vanish when the item got recycled. Its position is pure finger position, so it is
            // pinned 1:1 with no drift, whatever the list does underneath.
            if (drag != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, (pointerY.floatValue - drag.grabOffsetPx).roundToInt()) }
                        .shadow(12.dp),
                ) {
                    QueueRowContent(item = drag.ref.item, dragging = true)
                }
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

/** The row's visuals only - no gestures. Shared by the in-list row and the floating dragged copy. */
@Composable
private fun QueueRowContent(
    item: QueueItem,
    dragging: Boolean,
    rowHeight: Dp = QUEUE_ROW_HEIGHT,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (dragging) NamiColors.Ink700 else NamiColors.Ink900)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueTrackInfo(item = item, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.padding(start = 8.dp).size(40.dp),
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

// One row style for both sections. Removal is swipe-left only (no delete button); reordering is
// handled entirely by the parent (see the pointerInput on the LazyColumn), so this composable owns
// no drag state - being recycled mid-drag is harmless. While its item is the one being dragged it
// just renders transparent, leaving the gap the floating copy came out of.
@Composable
private fun QueueRow(
    item: QueueItem,
    hidden: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        SwipeToDismissBox(
            state = dismissState,
            // Only left (EndToStart, toward removal) is a real gesture here.
            enableDismissFromStartToEnd = false,
            modifier = Modifier.alpha(if (hidden) 0f else 1f),
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
            QueueRowContent(item = item, dragging = false)
        }
    }

    LaunchedEffect(removed) {
        if (removed) {
            delay(200)
            onRemove()
        }
    }
}
