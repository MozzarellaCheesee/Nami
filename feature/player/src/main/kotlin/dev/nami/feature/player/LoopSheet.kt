package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.ImmersiveSheetEffect
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.LoopRange
import dev.nami.domain.SavedLoop

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** План.md §22.2 "A-B петли с сохранением": mark A at the current position, keep listening/
 * scrubbing to where B should be, mark B (which activates the loop immediately - see
 * PlayerRepository.activeLoop), optionally save it under a name, or pick up a previously saved
 * one for this track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopSheet(
    currentPositionMs: Long,
    pendingStartMs: Long?,
    activeLoop: LoopRange?,
    savedLoops: List<SavedLoop>,
    onMarkStart: () -> Unit,
    onMarkEndAndActivate: (startMs: Long) -> Unit,
    onClearActive: () -> Unit,
    onSave: (startMs: Long, endMs: Long, name: String) -> Unit,
    onActivateSaved: (SavedLoop) -> Unit,
    onDeleteSaved: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveSheetEffect()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(text = "A-B петля", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)

            when {
                activeLoop != null -> {
                    Text(
                        text = "Играет петлёй: ${formatMs(activeLoop.startMs)} - ${formatMs(activeLoop.endMs)}",
                        color = NamiColors.Shu,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Сохранить",
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable { showSaveDialog = true },
                        )
                        Text(
                            text = "Выключить",
                            color = NamiColors.Paper70,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable { onClearActive() },
                        )
                    }
                }
                pendingStartMs != null -> {
                    Text(
                        text = "Точка A: ${formatMs(pendingStartMs)} - дослушай до нужного места и отметь B",
                        color = NamiColors.Paper70,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 16.dp)
                            .background(NamiColors.Paper100, RoundedCornerShape(12.dp))
                            .clickable { onMarkEndAndActivate(pendingStartMs) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(text = "Отметить B (${formatMs(currentPositionMs)})", color = NamiColors.Ink900, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    Text(
                        text = "Отметь начало и конец фрагмента, чтобы зациклить его",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 16.dp)
                            .background(NamiColors.Ink800, RoundedCornerShape(12.dp))
                            .clickable { onMarkStart() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(text = "Отметить A (${formatMs(currentPositionMs)})", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (savedLoops.isNotEmpty()) {
                Text(
                    text = "Сохранённые",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                savedLoops.forEach { loop ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onActivateSaved(loop) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = loop.name, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${formatMs(loop.startMs)} - ${formatMs(loop.endMs)}",
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onDeleteSaved(loop.id) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = NamiColors.Paper40)
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 8.dp))
        }
    }

    if (showSaveDialog && activeLoop != null) {
        var name by remember { mutableStateOf("") }
        NamiAlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Название петли", color = NamiColors.Paper100) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Например: припев") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(activeLoop.startMs, activeLoop.endMs, name.ifBlank { "Петля" })
                    showSaveDialog = false
                }) { Text("Сохранить", color = NamiColors.Shu) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Отмена", color = NamiColors.Paper70) }
            },
        )
    }
}
