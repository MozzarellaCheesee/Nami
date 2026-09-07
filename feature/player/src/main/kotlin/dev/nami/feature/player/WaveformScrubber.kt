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
    // План.md §22.1 "Метки моментов" -- (positionFraction 0..1, ARGB color) per marker, drawn as
    // a small triangle above the bar it lands on. No separate tap-to-jump handling needed: a
    // marker's fraction IS a point on the scrubber, so the existing tap-to-seek already lands
    // there when tapped.
    moments: List<Pair<Float, Int>> = emptyList(),
    onLongPress: (Float) -> Unit = {},
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
    val crossfade = remember(seedKey) { Animatable(0f) }
    LaunchedEffect(seedKey, isReal) {
        if (isReal) crossfade.animateTo(1f, animationSpec = tween(500)) else crossfade.snapTo(0f)
    }
    val heights = if (isReal) {
        placeholder.indices.map { i -> placeholder[i] + (realHeights!![i] - placeholder[i]) * crossfade.value }
    } else {
        placeholder
    }
    val barAlpha = if (isReal) 1f else loadingPulse

    Canvas(
        modifier = modifier
            .height(48.dp)
            .pointerInput(seedKey) {
                detectTapGestures(
                    onTap = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(fraction)
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

        val markerRadiusPx = 4.dp.toPx()
        moments.forEach { (fraction, colorArgb) ->
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawCircle(
                color = androidx.compose.ui.graphics.Color(colorArgb),
                radius = markerRadiusPx,
                center = Offset(x, markerRadiusPx),
            )
        }
    }
}
