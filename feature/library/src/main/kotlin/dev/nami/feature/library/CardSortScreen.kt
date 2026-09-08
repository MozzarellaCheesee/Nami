package dev.nami.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.Track

private const val SWIPE_THRESHOLD_PX = 300f

/** Группа D "карточный разбор библиотеки" - см. CardSortViewModel. Простой drag + порог, без
 * физики Apple-стиля (скорость/проекция) - для сортировки карточек этого достаточно. */
@Composable
fun CardSortScreen(onBack: () -> Unit, viewModel: CardSortViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Разбор библиотеки", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "Вправо - в любимые, влево - в корзину, вверх - пропустить",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )

        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when {
                uiState.loading -> Text("Загрузка...", color = NamiColors.Paper70)
                uiState.queue.isEmpty() -> Text("Библиотека разобрана", color = NamiColors.Paper70)
                else -> {
                    // Peek card behind, static - only the top card is draggable.
                    uiState.queue.getOrNull(1)?.let { peek ->
                        SortCard(track = peek, offset = Offset.Zero, modifier = Modifier.graphicsLayer { scaleX = 0.95f; scaleY = 0.95f; alpha = 0.6f })
                    }
                    val top = uiState.queue.first()
                    var offset by remember(top.id) { mutableStateOf(Offset.Zero) }
                    val animatedX by animateFloatAsState(offset.x, animationSpec = tween(150), label = "cardX")
                    val animatedY by animateFloatAsState(offset.y, animationSpec = tween(150), label = "cardY")
                    SortCard(
                        track = top,
                        offset = Offset(animatedX, animatedY),
                        modifier = Modifier.pointerInput(top.id) {
                            detectDragGestures(
                                onDrag = { change, drag -> change.consume(); offset += drag },
                                onDragEnd = {
                                    when {
                                        offset.x > SWIPE_THRESHOLD_PX -> viewModel.swipe(SwipeDirection.RIGHT)
                                        offset.x < -SWIPE_THRESHOLD_PX -> viewModel.swipe(SwipeDirection.LEFT)
                                        offset.y < -SWIPE_THRESHOLD_PX -> viewModel.swipe(SwipeDirection.UP)
                                        else -> offset = Offset.Zero
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SortCard(track: Track, offset: Offset, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .graphicsLayer { translationX = offset.x; translationY = offset.y; rotationZ = (offset.x / 40f).coerceIn(-15f, 15f) }
            .clip(RoundedCornerShape(20.dp))
            .background(NamiColors.Ink800),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (track.albumArtworkPath != null) {
                AsyncImage(model = track.albumArtworkPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink700), contentAlignment = Alignment.Center) {
                    Text("波", color = NamiColors.Ink500, style = MaterialTheme.typography.displayLarge)
                }
            }
        }
        Column(modifier = Modifier.padding(16.dp)) {
            Text(track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            track.artistName?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
        }
    }
}
