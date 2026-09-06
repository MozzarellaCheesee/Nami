package dev.nami.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape

/**
 * A clickable modifier whose press ripple fills the whole clipped [shape] instead of the small
 * unbounded circle IconButton/clickable use by default (that default ripple stays a fixed-radius
 * circle around the touch point regardless of how big the button's own background/shape is,
 * which reads as "the animation is only on a tiny part of the icon" on any custom-sized button).
 */
@Composable
fun Modifier.fullBlockClickable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .clip(shape)
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}
