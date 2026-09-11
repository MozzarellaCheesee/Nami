package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.ImmersiveSheetEffect
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.LoopRange
import dev.nami.domain.Moment

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Design mock 4.22 "Моменты и петли" - one combined sheet instead of the waveform's own
 * long-press dialog and the separate A-B LoopSheet living in two different places. Shows the
 * track's waveform with both the moments and the active loop drawn right on it, the loop's
 * current range, every labeled moment as a row, and two actions to add more of either. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsAndLoopsSheet(
    trackTitle: String,
    artistName: String?,
    waveformHeights: List<Float>?,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    moments: List<Moment>,
    activeLoop: LoopRange?,
    pendingLoopStartMs: Long?,
    onSeek: (Float) -> Unit,
    onAddMomentHere: () -> Unit,
    onMomentClick: (Moment) -> Unit,
    onMarkLoopStart: () -> Unit,
    onMarkLoopEnd: (startMs: Long) -> Unit,
    onClearLoop: () -> Unit,
    onExportClip: (startMs: Long, endMs: Long) -> Unit,
    onExportVideoClip: (startMs: Long, endMs: Long) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveSheetEffect()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Моменты и петли", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
            Row {
                Text(trackTitle, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                artistName?.let {
                    Text(" / $it", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
                }
            }

            val loopFractionRange = if (activeLoop != null && durationMs > 0) {
                (activeLoop.startMs.toFloat() / durationMs)..(activeLoop.endMs.toFloat() / durationMs)
            } else {
                null
            }
            WaveformScrubber(
                seedKey = "$trackTitle-$artistName",
                progress = progress,
                onSeek = onSeek,
                realHeights = waveformHeights,
                loopRange = loopFractionRange,
                moments = if (durationMs > 0) {
                    moments.map { MomentMarker(it.id, it.positionMs.toFloat() / durationMs, it.colorArgb) }
                } else {
                    emptyList()
                },
                onMomentClick = { id -> moments.firstOrNull { it.id == id }?.let(onMomentClick) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            if (activeLoop != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(NamiColors.Wakaba, RoundedCornerShape(2.dp)))
                    Text(
                        "Петля: ${formatMs(activeLoop.startMs)} - ${formatMs(activeLoop.endMs)}",
                        color = NamiColors.Wakaba,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    Text(
                        "WAV",
                        color = NamiColors.Ai,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { onExportClip(activeLoop.startMs, activeLoop.endMs) }.padding(end = 10.dp),
                    )
                    Text(
                        "Видео MP4",
                        color = NamiColors.Ai,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { onExportVideoClip(activeLoop.startMs, activeLoop.endMs) }.padding(end = 12.dp),
                    )
                    Text(
                        "Убрать",
                        color = NamiColors.Paper70,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onClearLoop),
                    )
                }
            } else if (pendingLoopStartMs != null) {
                Text(
                    "Точка A: ${formatMs(pendingLoopStartMs)} - дослушай до конца и отметь снова",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Text(
                "Метки",
                color = NamiColors.Paper40,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
            )
            if (moments.isEmpty()) {
                Text("Пока нет меток - долгий тап по волне или кнопка ниже", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
            } else {
                moments.sortedBy { it.positionMs }.forEach { moment ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onMomentClick(moment) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color(moment.colorArgb), CircleShape))
                        Text(
                            moment.label,
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                        Text(formatMs(moment.positionMs), color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                Box(
                    modifier = Modifier
                        .background(NamiColors.Ink800, RoundedCornerShape(20.dp))
                        .clickable(onClick = onAddMomentHere)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = NamiColors.Paper100, modifier = Modifier.size(16.dp))
                        Text("Метка здесь", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .background(NamiColors.Ink800, RoundedCornerShape(20.dp))
                        .clickable {
                            if (pendingLoopStartMs != null) onMarkLoopEnd(pendingLoopStartMs) else onMarkLoopStart()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (pendingLoopStartMs != null) "Отметить B (${formatMs(positionMs)})" else "Задать петлю",
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
