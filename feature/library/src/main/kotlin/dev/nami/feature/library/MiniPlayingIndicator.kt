package dev.nami.feature.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors

private data class BarSpec(val periodMs: Int, val minFraction: Float)

private val BARS = listOf(
    BarSpec(periodMs = 620, minFraction = 0.25f),
    BarSpec(periodMs = 480, minFraction = 0.35f),
    BarSpec(periodMs = 700, minFraction = 0.2f),
)

/** Three vertical bars that bounce continuously while [isPlaying], and sit at a fixed
 * mid-height when paused (still the current track, just not making sound right now). */
@Composable
fun MiniPlayingIndicator(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mini-playing-indicator")
    val fractions = BARS.map { bar ->
        if (isPlaying) {
            transition.animateFloat(
                initialValue = bar.minFraction,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(bar.periodMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar",
            ).value
        } else {
            0.55f
        }
    }
    Canvas(modifier = modifier.size(width = 14.dp, height = 14.dp)) {
        val barWidthPx = size.width / (BARS.size * 2 - 1)
        fractions.forEachIndexed { index, fraction ->
            val x = index * barWidthPx * 2 + barWidthPx / 2
            val barHeightPx = size.height * fraction
            drawLine(
                color = NamiColors.Shu,
                start = Offset(x, size.height - barHeightPx),
                end = Offset(x, size.height),
                strokeWidth = barWidthPx,
            )
        }
    }
}
