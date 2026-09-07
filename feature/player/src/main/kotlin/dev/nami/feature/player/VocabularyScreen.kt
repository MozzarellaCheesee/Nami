package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

/** План.md's "свой словарик" -- words tapped in the lyrics screen, kept with their line/track,
 * exportable as CSV for Anki import. */
@Composable
fun VocabularyScreen(
    onBack: () -> Unit,
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val words by viewModel.words.collectAsState()
    val exportedMessage by viewModel.exportedMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(exportedMessage) {
        exportedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissExportedMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
                }
                Text(text = "Мой словарик", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.exportCsv() }, enabled = words.isNotEmpty()) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = "Экспорт в CSV (Anki)", tint = NamiColors.Paper70)
                }
            }
            if (words.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Словарик пуст -- тапай слова в тексте песни", color = NamiColors.Paper70)
                }
            } else {
                LazyColumn {
                    items(words, key = { it.id }) { word ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = word.word, color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "「${word.reading}」",
                                        color = NamiColors.Paper70,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                if (word.meaning.isNotBlank()) {
                                    Text(text = word.meaning, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    text = "${word.contextLine} -- ${word.trackTitle}",
                                    color = NamiColors.Paper40,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { viewModel.remove(word.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Убрать", tint = NamiColors.Paper40)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
