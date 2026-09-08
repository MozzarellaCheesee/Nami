package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.ImmersiveSheetEffect
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius

private val PRESET_MINUTES = listOf(15, 30, 45, 60)

/** Real sleep timer, not a stub - picks a duration (or cancels a running one), backed by
 * PlayerRepository.startSleepTimer/cancelSleepTimer which actually pauses playback when it hits
 * zero. [remainingMs] drives the live countdown/cancel row when a timer is already running. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    remainingMs: Long?,
    onDismiss: () -> Unit,
    onStart: (minutes: Int) -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveSheetEffect()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(text = "Таймер сна", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
            if (remainingMs != null) {
                Text(
                    text = "Пауза через ${formatRemaining(remainingMs)}",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Выключить таймер",
                    color = NamiColors.Shu,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 24.dp)
                        .clickable { onCancel(); onDismiss() },
                )
            } else {
                Text(
                    text = "Плеер поставится на паузу по истечении времени",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    PRESET_MINUTES.forEach { minutes ->
                        Box(
                            modifier = Modifier
                                .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Button))
                                .clickable { onStart(minutes); onDismiss() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(text = "$minutes мин", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
