package dev.nami.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.domain.MusicBrainzCandidate
import kotlinx.coroutines.launch

/** A3 "Редактор тегов" (П.md §23.20) -- artist/album/year/genre fields, blank = leave unchanged
 * for that field (see LibraryRepositoryImpl.batchEditTracks for exact per-field semantics, e.g.
 * year with no album name applies to each track's existing album). Same dialog for one track
 * (⋮ "Редактировать теги" on a single row) or many (selection-mode batch edit) -- [trackCount]
 * only changes the title, the field semantics don't care how many tracks are behind them.
 * "Найти в MusicBrainz" searches by a manually typed title/artist and prefills the form from the
 * picked result -- no per-track auto-detection, even the single-track case leaves this manual so
 * the same code path covers both. */
@Composable
internal fun TagEditDialog(
    trackCount: Int,
    onSearchMusicBrainz: suspend (title: String, artist: String?) -> List<MusicBrainzCandidate>,
    onSave: (artistName: String?, albumName: String?, year: Int?, genre: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var artistName by remember { mutableStateOf("") }
    var albumName by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var searchTitle by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<MusicBrainzCandidate>>(emptyList()) }
    val scope = rememberCoroutineScope()

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (trackCount == 1) "Редактировать теги" else "Редактировать теги ($trackCount)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Пустое поле -- не менять.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = artistName, onValueChange = { artistName = it },
                    label = { Text("Исполнитель") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = albumName, onValueChange = { albumName = it },
                    label = { Text("Альбом") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = year, onValueChange = { year = it.filter { c -> c.isDigit() } },
                    label = { Text("Год") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = genre, onValueChange = { genre = it },
                    label = { Text("Жанр") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Поиск в MusicBrainz:", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchTitle, onValueChange = { searchTitle = it },
                        label = { Text("Название") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    IconButton(
                        enabled = searchTitle.isNotBlank() && !searching,
                        onClick = {
                            searching = true
                            scope.launch {
                                candidates = onSearchMusicBrainz(searchTitle, artistName.ifBlank { null })
                                searching = false
                            }
                        },
                    ) { Icon(Icons.Outlined.Search, contentDescription = "Искать") }
                }
                candidates.forEach { candidate ->
                    Text(
                        text = listOfNotNull(candidate.artistName, candidate.title, candidate.albumName, candidate.year?.toString())
                            .joinToString(" — "),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable {
                            candidate.artistName?.let { artistName = it }
                            candidate.albumName?.let { albumName = it }
                            candidate.year?.let { year = it.toString() }
                            candidate.genre?.let { genre = it }
                            candidates = emptyList()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    artistName.trim().ifBlank { null },
                    albumName.trim().ifBlank { null },
                    year.trim().toIntOrNull(),
                    genre.trim().ifBlank { null },
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
