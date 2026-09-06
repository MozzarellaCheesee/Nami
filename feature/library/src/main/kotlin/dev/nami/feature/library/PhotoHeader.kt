package dev.nami.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

/**
 * Drives a collapsing photo header that shrinks toward [minHeightPx] as the list below scrolls
 * down (freeing up scroll area) and grows back to [maxHeightPx] once the list is scrolled back to
 * the top -- a standard collapsing-toolbar handoff via [NestedScrollConnection]: this consumes
 * scroll deltas to resize the header BEFORE the list gets them (shrinking), and takes what the
 * list couldn't consume at its own top edge AFTER it scrolls (expanding), so the two never fight
 * over the same drag. Album/Artist detail screens each draw their own header content around this
 * (a floating cover that slides/shrinks into a compact row) rather than sharing one composable --
 * they differ enough (avatar vs square cover, extra rows) that a shared header was more
 * indirection than reuse.
 */
class CollapsingHeaderState(maxHeightPx: Float, private val minHeightPx: Float) {
    var heightPx by mutableFloatStateOf(maxHeightPx)
        private set
    private val maxHeightPx = maxHeightPx

    /** 0f fully expanded, 1f fully collapsed -- drives any scroll-reactive UI (e.g. a compact
     * header row's avatar shrinking as the photo collapses). */
    val collapseFraction: Float
        get() = ((maxHeightPx - heightPx) / (maxHeightPx - minHeightPx)).coerceIn(0f, 1f)

    /** Only two resting states exist -- fully expanded or fully collapsed -- so a gesture that
     * ends mid-transition (a small scroll then release, not a full swipe) doesn't leave the
     * header (and anything animated off its collapseFraction, like a sliding avatar) stuck
     * halfway. Call once the driving scroll's gesture ends (e.g. LazyListState.isScrollInProgress
     * going false). */
    suspend fun snapToNearestEdge() {
        val target = if (collapseFraction > 0.5f) minHeightPx else maxHeightPx
        androidx.compose.animation.core.animate(heightPx, target) { value, _ -> heightPx = value }
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0 && heightPx > minHeightPx) {
                val delta = max(available.y, minHeightPx - heightPx)
                heightPx += delta
                return Offset(0f, delta)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0 && heightPx < maxHeightPx) {
                val delta = min(available.y, maxHeightPx - heightPx)
                heightPx += delta
                return Offset(0f, delta)
            }
            return Offset.Zero
        }
    }
}

@Composable
fun rememberCollapsingHeaderState(maxHeight: Dp, minHeight: Dp): CollapsingHeaderState {
    val density = LocalDensity.current
    return remember {
        with(density) { CollapsingHeaderState(maxHeight.toPx(), minHeight.toPx()) }
    }
}
