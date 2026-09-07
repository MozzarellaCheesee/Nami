package dev.nami.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors
import kotlin.math.roundToInt
import kotlin.random.Random

private const val BAR_COUNT = 120
private const val BAR_WIDTH_DP = 2
private const val BAR_GAP_DP = 1
private const val MOMENT_HIT_RADIUS_DP = 12

/** One "Метки моментов" marker on the scrubber -- see WaveformScrubber's `moments` param. */
data class MomentMarker(val id: Long, val fraction: Float, val colorArgb: Int)

/** Placeholder shape shown before the real scan (see WaveformScanner/NowPlayingViewModel.waveform)
 * finishes, or if it fails -- deterministic per-track so it doesn't visibly jitter between
 * recompositions while loading, but NOT real amplitude data. */
private fun barHeights(seedKey: String): List<Float> {
    val random = Random(seedKey.hashCode())
    // A few sine-ish "phrases" layered with noise so it doesn't look uniformly random.
    return (0 until BAR_COUNT).map { i ->
        val phrase = (kotlin.math.sin(i / 9.0) * 0.5 + 0.5)
        val noise = random.nextFloat() * 0.5
        (phrase * 0.5 + noise).toFloat().coerceIn(0.15f, 1f)
    }
}

@Composable
fun WaveformScrubber(
    seedKey: String,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressPreview: (Float) -> Unit = {},
    onPreviewEnd: () -> Unit = {},
    // Real per-track amplitude (WaveformScanner), one value per bar, same size as the placeholder
    // -- null while it's still decoding or if the scan failed, in which case the placeholder
    // shape below is what's actually drawn.
    realHeights: List<Float>? = null,
    // План.md §22.1 "Метки моментов" -- drawn as a thin full-height tick (not a dot sitting on
    // the bar it lands on, which reads badly against an uneven waveform -- a dot's vertical
    // position has to pick some bar height to sit at, and any choice looks arbitrary/misaligned
    // next to neighboring bars of a different height; a full-height line has no such problem).
    // Tapping near one (see MOMENT_HIT_RADIUS_DP) calls onMomentClick instead of seeking, so
    // there's always a way to manage (rename/delete) a marker instead of just jumping to it.
    moments: List<MomentMarker> = emptyList(),
    onLongPress: (Float) -> Unit = {},
    onMomentClick: (Long) -> Unit = {},
    // Design mock 4.22 "Моменты и петли" -- the active A-B loop drawn as a translucent region on
    // the waveform itself, not just described in text above it. Null when no loop is active.
    loopRange: ClosedFloatingPointRange<Float>? = null,
) {
    val placeholder = remember(seedKey) { barHeights(seedKey) }
    val isReal = realHeights != null && realHeights.size == placeholder.size
    var dragProgress by remember(seedKey) { mutableStateOf<Float?>(null) }
    val displayedProgress = dragProgress ?: progress

    // Without this, the placeholder shape (deliberately made to look like a plausible waveform,
    // so it isn't a flat boring bar) silently morphs into the real one the instant the scan
    // finishes -- indistinguishable from a random unexplained UI change, since nothing marked
    // the placeholder as "still loading" in the first place. Two cues instead: a slow alpha pulse
    // on the not-yet-real shape (this bar is a stand-in, not final data), and a smooth animated
    // crossfade -- not an instant swap -- once the real one arrives, so the transition itself
    // reads as "this finished loading" rather than "something just changed".
    val loadingPulse by rememberInfiniteTransition(label = "waveform-loading-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "waveform-loading-pulse-alpha",
    )
    val targetHeights = if (isReal) realHeights!! else placeholder

    // Bars actually drawn morph from whatever was PREVIOUSLY on screen to targetHeights, whether
    // that's the same track's placeholder finishing its scan or a totally different track's shape
    // (switching tracks used to just hard-cut to the next track's bars, even when its real
    // waveform was already cached and ready). Not keyed by seedKey -- it has to survive a track
    // change to have something to morph FROM. Captures the animation's own current interpolated
    // position as the new start point (not the old target) so an overlapping second change
    // doesn't jump backward.
    var fromHeights by remember { mutableStateOf(targetHeights) }
    var toHeights by remember { mutableStateOf(targetHeights) }
    val morphProgress = remember { Animatable(1f) }
    LaunchedEffect(targetHeights) {
        if (targetHeights == toHeights) return@LaunchedEffect
        val t = morphProgress.value
        fromHeights = List(toHeights.size) { i -> fromHeights.getOrElse(i) { toHeights[i] } + (toHeights[i] - fromHeights.getOrElse(i) { toHeights[i] }) * t }
        toHeights = targetHeights
        morphProgress.snapTo(0f)
        morphProgress.animateTo(1f, animationSpec = tween(500))
    }
    val heights = if (fromHeights.size == toHeights.size) {
        List(toHeights.size) { i -> fromHeights[i] + (toHeights[i] - fromHeights[i]) * morphProgress.value }
    } else {
        toHeights
    }
    val barAlpha = if (isReal) 1f else loadingPulse

    Canvas(
        modifier = modifier
            .height(48.dp)
            .pointerInput(seedKey, moments) {
                val hitRadiusPx = MOMENT_HIT_RADIUS_DP.dp.toPx()
                detectTapGestures(
                    onTap = { offset ->
                        val nearest = moments.minByOrNull { kotlin.math.abs(it.fraction * size.width - offset.x) }
                        if (nearest != null && kotlin.math.abs(nearest.fraction * size.width - offset.x) <= hitRadiusPx) {
                            onMomentClick(nearest.id)
                        } else {
                            onSeek((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    },
                    onLongPress = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onLongPress(fraction)
                    },
                )
            }
            .pointerInput(seedKey) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        dragProgress = fraction
                        onProgressPreview(fraction)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = fraction
                        onProgressPreview(fraction)
                    },
                    onDragEnd = {
                        dragProgress?.let(onSeek)
                        dragProgress = null
                        onPreviewEnd()
                    },
                    onDragCancel = {
                        dragProgress = null
                        onPreviewEnd()
                    },
                )
            },
    ) {
        // Bars are laid out as a fraction of the actual canvas width, not a fixed dp step --
        // a fixed step (BAR_COUNT * (barWidth+gap)) rarely equals the real measured width,
        // leaving the bars stuck to one side with a gap on the other, and desyncing the played
        // portion (computed from the real width) from where the bars themselves are drawn.
        loopRange?.let { range ->
            val startX = range.start.coerceIn(0f, 1f) * size.width
            val endX = range.endInclusive.coerceIn(0f, 1f) * size.width
            drawRect(
                color = NamiColors.Wakaba.copy(alpha = 0.16f),
                topLeft = Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size(endX - startX, size.height),
            )
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            drawRect(
                color = NamiColors.Wakaba,
                topLeft = Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size(endX - startX, size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
            )
        }
        val step = size.width / BAR_COUNT
        val barWidthPx = step * (BAR_WIDTH_DP.toFloat() / (BAR_WIDTH_DP + BAR_GAP_DP))
        val playedBars = (heights.size * displayedProgress).roundToInt()
        heights.forEachIndexed { index, heightFraction ->
            val barHeightPx = size.height * heightFraction
            val x = index * step
            drawLine(
                color = (if (index < playedBars) NamiColors.Shu else NamiColors.Ink500).copy(alpha = barAlpha),
                start = Offset(x, (size.height - barHeightPx) / 2f),
                end = Offset(x, (size.height + barHeightPx) / 2f),
                strokeWidth = barWidthPx,
            )
        }
        val headX = displayedProgress * size.width
        drawLine(
            color = NamiColors.Paper100,
            start = Offset(headX, 0f),
            end = Offset(headX, size.height),
            strokeWidth = 2.dp.toPx(),
        )

        // Full-height, low-alpha tick (visible over any bar height, no arbitrary "which height
        // does the dot sit at" choice) plus a small solid flag at the very top so it also reads
        // as a distinct, tappable thing rather than just a faint stripe.
        val flagWidthPx = 3.dp.toPx()
        val flagHeightPx = 8.dp.toPx()
        moments.forEach { marker ->
            val x = marker.fraction.coerceIn(0f, 1f) * size.width
            val color = androidx.compose.ui.graphics.Color(marker.colorArgb)
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
            drawRect(
                color = color,
                topLeft = Offset(x - flagWidthPx / 2f, 0f),
                size = androidx.compose.ui.geometry.Size(flagWidthPx, flagHeightPx),
            )
        }
    }
}
