package dev.nami.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.fullBlockClickable
import dev.nami.core.designsystem.namiBlur
import dev.nami.domain.PlaybackState

/** Группа E "дорожный режим" - честно узкий скоуп: крупная упрощённая раскладка (огромные кнопки,
 * крупный текст) для безопасного управления одним касанием за рулём, без взгляда на список.
 * Открывается вручную из Настроек или из меню "Ещё" плеера - автоматическое включение по
 * подключению Bluetooth-автомагнитолы ненадёжно проверяемо на всех устройствах без реального
 * тестирования в машине, не рискнул.
 *
 * Вёрстка намеренно из трёх зон: обложка (единственный ориентир "что играет" боковым зрением),
 * текст, ряд кнопок у нижнего края - там, где до экрана дотягивается рука, не отрывая её от руля.
 * Ничего мелкого и никаких списков: за рулём на экран смотрят долями секунды. */
@Composable
fun DriveModeScreen(onBack: () -> Unit, viewModel: NowPlayingViewModel = hiltViewModel()) {
    val state by viewModel.playbackState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val playing = state as? PlaybackState.Playing
    val isPlaying = playing?.isPlaying == true
    val track = queue.nowPlaying
    val artwork = track?.artworkPath

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        // Тот же приём, что на Now Playing: своя обложка, размытая в фон - экран перестаёт быть
        // чёрным прямоугольником и на периферийном зрении читается как "играет вот это".
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().namiBlur(72.dp),
            )
            Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900.copy(alpha = 0.78f)))
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(12.dp).size(56.dp).fullBlockClickable(shape = CircleShape, onClick = onBack),
            ) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper70, modifier = Modifier.size(32.dp))
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .aspectRatio(1f)
                        .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.Card)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artwork != null) {
                        AsyncImage(
                            model = artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = NamiColors.Paper40, modifier = Modifier.size(64.dp))
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        track?.title ?: "Ничего не играет",
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    track?.artistName?.let {
                        Text(
                            it,
                            color = NamiColors.Paper70,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DriveModeButton(Icons.Rounded.SkipPrevious, "Предыдущий", 84.dp) { viewModel.skipToPreviousTrack() }
                    DriveModeButton(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Играть",
                        size = 116.dp,
                        background = NamiColors.Shu,
                    ) { viewModel.toggle() }
                    DriveModeButton(Icons.Rounded.SkipNext, "Следующий", 84.dp) { viewModel.skipNext() }
                }
            }
        }
    }
}

@Composable
private fun DriveModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    background: androidx.compose.ui.graphics.Color = NamiColors.Ink700,
    onClick: () -> Unit,
) {
    // Press-scale вместо ripple как основная обратная связь: за рулём на экран не смотрят, палец
    // закрывает кнопку целиком, и заметно только то, что двигается под самим пальцем.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "drive-button-press",
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = NamiColors.Paper100, modifier = Modifier.size(size * 0.46f))
    }
}
