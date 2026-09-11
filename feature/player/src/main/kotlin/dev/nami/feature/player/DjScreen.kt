package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.domain.DjDeck
import dev.nami.domain.DjDeckState

/** DJ-режим с двумя деками, ручным кроссфейдером, авто-битмэтчингом и регулировкой темпа. */
@Composable
fun DjScreen(onBack: () -> Unit, viewModel: DjViewModel = hiltViewModel()) {
    val deckA by viewModel.deckA.collectAsState()
    val deckB by viewModel.deckB.collectAsState()
    val crossfade by viewModel.crossfade.collectAsState()
    val tracks by viewModel.tracks.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("DJ-режим", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "Два независимых плеера, ручной кроссфейдер и авто-битмэтчинг (SYNC / Pitch / Nudge).",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            DeckCard(
                label = "A",
                state = deckA,
                onToggle = { viewModel.toggleDeck(DjDeck.A) },
                onSync = { viewModel.syncBpm(targetDeck = DjDeck.A, sourceDeck = DjDeck.B) },
                onPitchDown = { viewModel.adjustSpeed(DjDeck.A, -0.01f) },
                onPitchUp = { viewModel.adjustSpeed(DjDeck.A, +0.01f) },
                onResetPitch = { viewModel.resetSpeed(DjDeck.A) },
                onNudgeBack = { viewModel.nudge(DjDeck.A, -50L) },
                onNudgeForward = { viewModel.nudge(DjDeck.A, +50L) },
                modifier = Modifier.weight(1f),
            )
            DeckCard(
                label = "B",
                state = deckB,
                onToggle = { viewModel.toggleDeck(DjDeck.B) },
                onSync = { viewModel.syncBpm(targetDeck = DjDeck.B, sourceDeck = DjDeck.A) },
                onPitchDown = { viewModel.adjustSpeed(DjDeck.B, -0.01f) },
                onPitchUp = { viewModel.adjustSpeed(DjDeck.B, +0.01f) },
                onResetPitch = { viewModel.resetSpeed(DjDeck.B) },
                onNudgeBack = { viewModel.nudge(DjDeck.B, -50L) },
                onNudgeForward = { viewModel.nudge(DjDeck.B, +50L) },
                modifier = Modifier.weight(1f),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Кроссфейдер: A ← → B", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = crossfade,
                onValueChange = { viewModel.setCrossfade(it) },
                colors = SliderDefaults.colors(thumbColor = NamiColors.Shu, activeTrackColor = NamiColors.Shu),
            )
        }
        Text(
            "Библиотека - жми A/B чтобы загрузить трек в деку",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tracks, key = { it.id.value }) { track ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        val bpm = track.bpm
                        val subtitle = buildString {
                            append(track.artistName ?: "")
                            if (bpm != null && bpm > 0) {
                                if (isNotEmpty()) append(" • ")
                                append("%.0f BPM".format(bpm))
                            }
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(subtitle, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                    TextButton(onClick = { viewModel.loadDeck(DjDeck.A, track) }) { Text("A", color = NamiColors.Shu) }
                    TextButton(onClick = { viewModel.loadDeck(DjDeck.B, track) }) { Text("B", color = NamiColors.Ai) }
                }
            }
        }
    }
}

@Composable
private fun DeckCard(
    label: String,
    state: DjDeckState,
    onToggle: () -> Unit,
    onSync: () -> Unit,
    onPitchDown: () -> Unit,
    onPitchUp: () -> Unit,
    onResetPitch: () -> Unit,
    onNudgeBack: () -> Unit,
    onNudgeForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = if (label == "A") NamiColors.Shu else NamiColors.Ai

    Column(
        modifier = modifier
            .padding(4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Дека $label", color = accentColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            val bpmText = state.effectiveBpm?.let { "%.1f BPM".format(it) }
                ?: state.baseBpm?.let { "%.1f BPM".format(it) }
                ?: "— BPM"
            Text(bpmText, color = NamiColors.Kin, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(modifier = Modifier.padding(top = 4.dp))

        Text(
            state.track?.title ?: "Пусто",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.track?.artistName ?: "—",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                    tint = NamiColors.Paper100,
                )
            }
            Text(
                "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}",
                color = NamiColors.Paper40,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // Pitch and SYNC
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "-",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable { onPitchDown() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    "%.2fx".format(state.speed),
                    color = if (state.speed == 1.0f) NamiColors.Paper100 else NamiColors.Kin,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clickable { onResetPitch() }
                        .padding(horizontal = 2.dp),
                )
                Text(
                    "+",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable { onPitchUp() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Button(
                onClick = onSync,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = NamiColors.Paper100,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("SYNC", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Nudge
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "« -50ms",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable { onNudgeBack() }
                    .padding(vertical = 4.dp),
            )
            Text(
                "+50ms »",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable { onNudgeForward() }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
