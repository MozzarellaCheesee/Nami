package dev.nami.feature.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors

/** Design mock 4.26 "Skeleton -- загрузка библиотеки" -- shown while Paging's initial page is
 * still loading (LoadState.Loading), instead of the same "empty library" message a genuinely
 * empty library shows (those used to be indistinguishable -- itemCount == 0 either way). */
@Composable
fun LibrarySkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    val color = NamiColors.Ink700.copy(alpha = alpha)

    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Bone(color, width = 160.dp, height = 24.dp)
        Row(modifier = Modifier.padding(top = 16.dp)) {
            repeat(3) { i ->
                Bone(color, width = 72.dp, height = 32.dp, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(end = if (i < 2) 8.dp else 0.dp))
            }
        }
        Bone(color, width = 200.dp, height = 16.dp, modifier = Modifier.padding(top = 20.dp))
        Row(modifier = Modifier.padding(top = 12.dp)) {
            repeat(3) { i ->
                Bone(color, width = 96.dp, height = 96.dp, modifier = Modifier.padding(end = if (i < 2) 8.dp else 0.dp))
            }
        }
        repeat(4) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Bone(color, width = 44.dp, height = 44.dp)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Bone(color, width = 180.dp, height = 14.dp)
                    Bone(color, width = 120.dp, height = 12.dp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Bone(
    color: androidx.compose.ui.graphics.Color,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp),
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(modifier = modifier.size(width, height).background(color, shape))
}
