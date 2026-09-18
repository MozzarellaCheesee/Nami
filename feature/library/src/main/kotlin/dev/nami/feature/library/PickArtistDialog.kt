package dev.nami.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.model.ArtistId
import kotlinx.coroutines.launch

/** Attach/reattach an album to an existing library artist, or type a name that isn't in the
 * library yet - the picker alone had no way to credit an artist who has no tracks imported yet
 * (mirrors how AddTracksToArtistDialog treats new-file import as a separate concern). */
@Composable
fun PickArtistDialog(
    onPick: (ArtistId) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PickArtistViewModel = hiltViewModel(),
) {
    val artists = viewModel.artists.collectAsLazyPagingItems()
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбрать артиста") },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(128) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Новый артист") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val name = newName
                            scope.launch {
                                viewModel.createArtist(name)?.let(onPick)
                            }
                        },
                    ) { Text("Создать") }
                }
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(count = artists.itemCount, key = artists.itemKey { it.id.value }) { index ->
                        val artist = artists[index] ?: return@items
                        ArtistListItem(artist = artist, onClick = { onPick(artist.id) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
