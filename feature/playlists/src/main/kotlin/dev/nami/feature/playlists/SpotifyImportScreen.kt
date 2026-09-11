package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiScreenHeader
import dev.nami.core.designsystem.NamiType
import dev.nami.core.model.PlaylistId
import dev.nami.domain.HybridCandidate
import dev.nami.domain.HybridImportItem
import dev.nami.domain.HybridTrackStatus

@Composable
fun SpotifyImportScreen(
    onBack: () -> Unit,
    onPlaylistCreated: (PlaylistId) -> Unit,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val url by viewModel.url.collectAsState()
    val hybridProgress by viewModel.hybridProgress.collectAsState()
    val reviewItem by viewModel.reviewItem.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        if (state is SpotifyImportState.Assembled) {
            val playlistId = (state as SpotifyImportState.Assembled).playlistId
            onPlaylistCreated(playlistId)
        }
    }

    Scaffold(
        containerColor = NamiColors.Ink900,
        snackbarHost = { dev.nami.core.designsystem.NamiSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(NamiColors.Ink900)
        ) {
            NamiScreenHeader(
                title = "Импорт из Spotify",
                onBack = onBack,
            )

            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = viewModel::onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ссылка на плейлист", color = NamiColors.Paper40) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NamiColors.Paper100,
                        unfocusedTextColor = NamiColors.Paper100,
                        focusedBorderColor = NamiColors.Shu,
                        unfocusedBorderColor = NamiColors.Ink600,
                        cursorColor = NamiColors.Shu,
                    ),
                )

                NamiPill(
                    text = "Анализировать",
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = viewModel::analyze,
                    enabled = url.isNotBlank() && state !is SpotifyImportState.Loading
                )
            }

            when (val currentState = state) {
                is SpotifyImportState.Idle -> {}
                is SpotifyImportState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NamiColors.Shu)
                    }
                }
                is SpotifyImportState.Error -> {
                    Text(
                        text = currentState.message,
                        color = NamiColors.Shu,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
                is SpotifyImportState.Result -> {
                    val analysis = currentState.analysis
                    val matchedCount = analysis.matches.count { it.localTrackId != null }
                    val unmatchedCount = analysis.matches.size - matchedCount
                    val progress = hybridProgress

                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = analysis.playlistName,
                                color = NamiColors.Paper100,
                                style = NamiType.TrackTitle
                            )
                            if (progress != null) {
                                Text(
                                    text = "✓ ${progress.done} скачано · ⚠ ${progress.needsReview} на проверке · ✗ ${progress.notFound} не найдено",
                                    color = NamiColors.Wakaba,
                                    style = NamiType.Secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            } else {
                                Text(
                                    text = "✅ $matchedCount найдено · ❌ $unmatchedCount не найдено",
                                    color = NamiColors.Paper70,
                                    style = NamiType.Secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 20.dp)) {
                            items(analysis.matches) { item ->
                                val hybridItem = progress?.items?.firstOrNull {
                                    it.spotifyTitle == item.spotifyTitle && it.spotifyArtist == item.spotifyArtist
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val (icon, color) = when {
                                        item.localTrackId != null || hybridItem?.status == HybridTrackStatus.DONE ->
                                            Icons.Outlined.CheckCircle to NamiColors.Wakaba
                                        hybridItem?.status == HybridTrackStatus.NEEDS_REVIEW ->
                                            Icons.Outlined.Warning to NamiColors.Ai
                                        hybridItem?.status == HybridTrackStatus.DOWNLOADING || hybridItem?.status == HybridTrackStatus.SEARCHING_VK ->
                                            Icons.Outlined.CloudDownload to NamiColors.Ai
                                        else ->
                                            Icons.Outlined.Cancel to NamiColors.Shu
                                    }

                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.spotifyTitle,
                                            color = NamiColors.Paper100,
                                            style = NamiType.TrackTitle,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.spotifyArtist,
                                            color = NamiColors.Paper70,
                                            style = NamiType.Secondary,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))

                                    when {
                                        item.localTrackId != null -> {
                                            Text(text = "В библиотеке", color = NamiColors.Wakaba, style = NamiType.Secondary)
                                        }
                                        hybridItem?.status == HybridTrackStatus.DONE -> {
                                            Text(text = "Скачано из ВК", color = NamiColors.Wakaba, style = NamiType.Secondary)
                                        }
                                        hybridItem?.status == HybridTrackStatus.DOWNLOADING -> {
                                            Text(text = "Скачивается...", color = NamiColors.Ai, style = NamiType.Secondary)
                                        }
                                        hybridItem?.status == HybridTrackStatus.SEARCHING_VK -> {
                                            Text(text = "Поиск в ВК...", color = NamiColors.Paper40, style = NamiType.Secondary)
                                        }
                                        hybridItem?.status == HybridTrackStatus.NEEDS_REVIEW -> {
                                            NamiPill(
                                                text = "Выбрать (${hybridItem.candidates.size})",
                                                onClick = { viewModel.openReviewDialog(hybridItem) }
                                            )
                                        }
                                        hybridItem?.status == HybridTrackStatus.NOT_FOUND -> {
                                            Text(text = "Не найдено в ВК", color = NamiColors.Shu, style = NamiType.Secondary)
                                        }
                                        else -> {
                                            Text(text = "Нет в библиотеке", color = NamiColors.Shu, style = NamiType.Secondary)
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (unmatchedCount > 0 && viewModel.isVkConfigured && progress == null) {
                                NamiPill(
                                    text = "Скачать недостающие из ВК ($unmatchedCount) и собрать",
                                    onClick = { viewModel.startHybridDownload(analysis.playlistName) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (unmatchedCount > 0 && !viewModel.isVkConfigured) {
                                Text(
                                    text = "Подключите токен ВК в настройках источников, чтобы скачать недостающие $unmatchedCount треков",
                                    color = NamiColors.Paper40,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            if (matchedCount > 0 && progress == null) {
                                NamiPill(
                                    text = "Собрать только из найденных ($matchedCount)",
                                    onClick = { viewModel.assemblePlaylist(analysis.playlistName) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                is SpotifyImportState.Assembled -> {}
            }
        }
    }

    reviewItem?.let { item ->
        VkCandidateSelectionDialog(
            item = item,
            onSelect = { candidate -> viewModel.resolveCandidate(item, candidate) },
            onDismiss = viewModel::dismissReviewDialog,
        )
    }
}

@Composable
private fun VkCandidateSelectionDialog(
    item: HybridImportItem,
    onSelect: (HybridCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "Выберите вариант из ВК", color = NamiColors.Paper100, style = NamiType.TrackTitle)
                Text(
                    text = "${item.spotifyArtist} - ${item.spotifyTitle}",
                    color = NamiColors.Paper70,
                    style = NamiType.Secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                items(item.candidates) { candidate ->
                    val minutes = candidate.durationSec / 60
                    val seconds = (candidate.durationSec % 60).toString().padStart(2, '0')
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(candidate) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = candidate.title, color = NamiColors.Paper100, style = NamiType.TrackTitle)
                            Text(
                                text = "${candidate.artist} · $minutes:$seconds ${candidate.reason?.let { "· $it" } ?: ""}",
                                color = NamiColors.Paper40,
                                style = NamiType.Secondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Пропустить", color = NamiColors.Paper70)
            }
        },
    )
}
