package dev.nami.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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

/** Deterministic per-track bar heights (0f..1f) -- no real amplitude data is available, so
 * this reads as a plausible waveform shape rather than a flat EQ ladder, seeded so the same
 * track always draws the same shape. */
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
) {
    val heights = remember(seedKey) { barHeights(seedKey) }
    var dragProgress by remember(seedKey) { mutableStateOf<Float?>(null) }
    val displayedProgress = dragProgress ?: progress

    Canvas(
        modifier = modifier
            .height(48.dp)
            .pointerInput(seedKey) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(seedKey) {
                detectDragGestures(
                    onDragStart = { offset -> dragProgress = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragProgress?.let(onSeek)
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                )
            },
    ) {
        val barWidthPx = BAR_WIDTH_DP.dp.toPx()
        val gapPx = BAR_GAP_DP.dp.toPx()
        val step = barWidthPx + gapPx
        val playedBars = (heights.size * displayedProgress).roundToInt()
        heights.forEachIndexed { index, heightFraction ->
            val barHeightPx = size.height * heightFraction
            val x = index * step
            drawLine(
                color = if (index < playedBars) NamiColors.Shu else NamiColors.Ink500,
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
    }
}
