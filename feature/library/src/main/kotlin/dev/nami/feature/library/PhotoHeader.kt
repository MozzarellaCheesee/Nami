package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import kotlin.math.max
import kotlin.math.min

/**
 * Photo header for Album/Artist detail screens per Дизайн.md §4: the cover/photo fills the top,
 * a bottom gradient fades it into the screen background, the back button floats over the photo
 * (not in a colored app-bar strip), and [content] (title/artist/play button) sits at the bottom
 * of the header, already legible against the gradient.
 */
@Composable
fun PhotoHeader(
    photoPath: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 360.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    // clipToBounds: as the header shrinks (see CollapsingHeaderState), a long title in [content]
    // can measure taller than the current height -- without a clip, that overflow bleeds past
    // the header's edges into the track list below instead of just getting cropped.
    Box(modifier = modifier.fillMaxWidth().height(height).clipToBounds()) {
        if (photoPath != null) {
            AsyncImage(
                model = photoPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(height),
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(height).background(NamiColors.Ink700))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1f to NamiColors.Ink900,
                    ),
                ),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 12.dp),
        ) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
        }
        Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            content()
        }
    }
}

/**
 * Drives a [PhotoHeader] that shrinks toward [minHeight] as the list below scrolls down (freeing
 * up scroll area) and grows back to [maxHeight] once the list is scrolled back to the top --
 * a standard collapsing-toolbar handoff via [NestedScrollConnection]: this consumes scroll deltas
 * to resize the header BEFORE the list gets them (shrinking), and takes what the list couldn't
 * consume at its own top edge AFTER it scrolls (expanding), so the two never fight over the
 * same drag.
 */
class CollapsingHeaderState(maxHeightPx: Float, private val minHeightPx: Float) {
    var heightPx by mutableFloatStateOf(maxHeightPx)
        private set
    private val maxHeightPx = maxHeightPx

    /** 0f fully expanded, 1f fully collapsed -- drives any scroll-reactive UI (e.g. a compact
     * header row's avatar shrinking as the photo collapses). */
    val collapseFraction: Float
        get() = ((maxHeightPx - heightPx) / (maxHeightPx - minHeightPx)).coerceIn(0f, 1f)

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
