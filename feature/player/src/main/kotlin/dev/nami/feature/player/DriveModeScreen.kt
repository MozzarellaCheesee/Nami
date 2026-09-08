package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.PlaybackState

/** Группа E "дорожный режим" - честно узкий скоуп: крупная упрощённая раскладка (огромные кнопки,
 * крупный текст) для безопасного управления одним касанием за рулём, без взгляда на список.
 * Только вручную из Настроек - автоматическое включение по подключению Bluetooth-автомагнитолы
 * ненадёжно проверяемо на всех устройствах без реального тестирования в машине, не рискнул. */
@Composable
fun DriveModeScreen(onBack: () -> Unit, viewModel: NowPlayingViewModel = hiltViewModel()) {
    val state by viewModel.playbackState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val playing = state as? PlaybackState.Playing
    val isPlaying = playing?.isPlaying == true

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper70, modifier = Modifier.size(28.dp))
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                queue.nowPlaying?.title ?: "Ничего не играет",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            queue.nowPlaying?.artistName?.let {
                Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 48.dp),
            ) {
                DriveModeButton(icon = Icons.Outlined.SkipPrevious, contentDescription = "Предыдущий", size = 72.dp, onClick = { viewModel.skipToPreviousTrack() })
                DriveModeButton(
                    icon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Играть",
                    size = 104.dp,
                    background = NamiColors.Shu,
                    onClick = { viewModel.toggle() },
                )
                DriveModeButton(icon = Icons.Outlined.SkipNext, contentDescription = "Следующий", size = 72.dp, onClick = { viewModel.skipNext() })
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
    Box(
        modifier = Modifier.size(size).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(size)) {
            Icon(icon, contentDescription = contentDescription, tint = NamiColors.Paper100, modifier = Modifier.size(size * 0.5f))
        }
    }
}
