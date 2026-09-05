package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors

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
    Box(modifier = modifier.fillMaxWidth().height(height)) {
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
            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
        }
        Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            content()
        }
    }
}
