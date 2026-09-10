package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.model.Track
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState

/** Nav entry point - route only carries two track ids, loads the actual [Track]s first. */
@Composable
fun ABCompareRoute(onBack: () -> Unit, entryViewModel: ABCompareEntryViewModel = hiltViewModel()) {
    val (trackA, trackB) = entryViewModel.tracks.collectAsState().value
    if (trackA != null && trackB != null) {
        ABCompareScreen(trackA = trackA, trackB = trackB, onBack = onBack)
    }
}

/** Хвост группы C "слепое A/B сравнение версий" - см. ABCompareViewModel для смысла ярлыков. */
@Composable
fun ABCompareScreen(trackA: Track, trackB: Track, onBack: () -> Unit, viewModel: ABCompareViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    LaunchedEffect(trackA.id, trackB.id) {
        viewModel.start(trackA.toPlayableTrack(), trackB.toPlayableTrack())
    }

    // verticalScroll - в альбомной ориентации на телефоне высоты не хватает, и кнопка
    // "Раскрыть названия" оказывалась за нижним краем.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                "Слепое сравнение",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            "Оба варианта играют под нейтральными именами - переключай и слушай, названия раскроются отдельной кнопкой.",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            VariantRow(
                label = "Вариант 1",
                revealedTitle = if (uiState.revealed) trackA.title else null,
                isPlaying = uiState.currentLabel == 1 && (playbackState as? PlaybackState.Playing)?.isPlaying == true,
                onClick = { viewModel.switchTo(1) },
            )
            VariantRow(
                label = "Вариант 2",
                revealedTitle = if (uiState.revealed) trackB.title else null,
                isPlaying = uiState.currentLabel == 2 && (playbackState as? PlaybackState.Playing)?.isPlaying == true,
                onClick = { viewModel.switchTo(2) },
            )
        }
        if (!uiState.revealed) {
            TextButton(onClick = { viewModel.reveal() }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Раскрыть названия", color = NamiColors.Shu)
            }
        }
    }
}

@Composable
private fun VariantRow(label: String, revealedTitle: String?, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(if (isPlaying) NamiColors.Ink700 else NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(if (isPlaying) NamiColors.Shu else NamiColors.Ink600, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = NamiColors.Paper100)
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(label, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
            revealedTitle?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun Track.toPlayableTrack() = PlayableTrack(
    id = id,
    title = title,
    artistName = artistName,
    path = path,
    artworkPath = albumArtworkPath,
    format = format,
    durationMs = durationMs,
    cueStartMs = cueStartMs,
    cueEndMs = cueEndMs,
)
