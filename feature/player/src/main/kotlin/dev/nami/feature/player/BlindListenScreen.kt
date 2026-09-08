package dev.nami.feature.player

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.PlaybackState

/** Группа D "слепое прослушивание" - см. BlindListenViewModel. */
@Composable
fun BlindListenScreen(onBack: () -> Unit, viewModel: BlindListenViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isPlaying = (playbackState as? PlaybackState.Playing)?.isPlaying == true

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Слепое прослушивание", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        when {
            uiState.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            uiState.current == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Библиотека пуста", color = NamiColors.Paper70)
            }
            else -> {
                val track = uiState.current!!
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .background(NamiColors.Ink700, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (uiState.revealed && track.albumArtworkPath != null) {
                            AsyncImage(model = track.albumArtworkPath, contentDescription = null, modifier = Modifier.fillMaxSize())
                        } else {
                            // A text glyph (even ASCII "?") sits off-center within its own line
                            // box depending on font metrics -- an Icon is drawn to fill its exact
                            // bounding box, no font-dependent guesswork needed to center it.
                            Icon(
                                Icons.Outlined.QuestionMark,
                                contentDescription = null,
                                tint = NamiColors.Paper40,
                                modifier = Modifier.size(96.dp),
                            )
                        }
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        if (uiState.revealed) track.title else "???",
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (uiState.revealed) track.artistName ?: "—" else "???",
                        color = NamiColors.Paper70,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isLiked by viewModel.isCurrentTrackLiked.collectAsState()
                        IconButton(onClick = viewModel::toggleLike) {
                            Icon(
                                if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isLiked) "Убрать из любимых" else "В любимые",
                                tint = if (isLiked) NamiColors.Shu else NamiColors.Paper100,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        IconButton(onClick = viewModel::togglePlayback) {
                            Icon(
                                if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Играть",
                                tint = NamiColors.Paper100,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        IconButton(onClick = viewModel::next) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = "Следующий", tint = NamiColors.Paper100, modifier = Modifier.size(40.dp))
                        }
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

                    if (!uiState.revealed) {
                        Button(onClick = viewModel::reveal) { Text("Раскрыть") }
                    } else {
                        TextButton(onClick = viewModel::next) { Text("Следующий трек", color = NamiColors.Shu) }
                    }
                }
            }
        }
    }
}
