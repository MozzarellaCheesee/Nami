package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.Track
import dev.nami.domain.DjDeck
import dev.nami.domain.DjDeckState

/** Хвост группы C "DJ-режим" -- см. DjRepository для честного скоупа. */
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
            "Два независимых плеера и ручной кроссфейдер -- без синхронизации темпа и битмэтчинга.",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            DeckCard(label = "A", state = deckA, onToggle = { viewModel.toggleDeck(DjDeck.A) }, modifier = Modifier.weight(1f))
            DeckCard(label = "B", state = deckB, onToggle = { viewModel.toggleDeck(DjDeck.B) }, modifier = Modifier.weight(1f))
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Кроссфейдер: A ← → B", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = crossfade,
                onValueChange = { viewModel.setCrossfade(it) },
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = NamiColors.Shu, activeTrackColor = NamiColors.Shu),
            )
        }
        Text(
            "Библиотека -- жми A/B чтобы загрузить трек в деку",
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
                        track.artistName?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                    TextButton(onClick = { viewModel.loadDeck(DjDeck.A, track) }) { Text("A", color = NamiColors.Shu) }
                    TextButton(onClick = { viewModel.loadDeck(DjDeck.B, track) }) { Text("B", color = NamiColors.Ai) }
                }
            }
        }
    }
}

@Composable
private fun DeckCard(label: String, state: DjDeckState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text("Дека $label", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
        Text(
            state.track?.title ?: "Пусто",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        state.track?.artistName?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 0.dp, top = 8.dp, end = 0.dp, bottom = 0.dp)) {
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
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
