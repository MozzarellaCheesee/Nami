package dev.nami.feature.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.TrackId

@Composable
fun AddToPlaylistDialog(
    trackIds: Set<TrackId>,
    onDismiss: () -> Unit,
    viewModel: AddToPlaylistViewModel = hiltViewModel(),
) {
    val playlists = viewModel.playlists.collectAsLazyPagingItems()
    var newPlaylistName by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("В плейлист") },
        text = {
            if (newPlaylistName != null) {
                OutlinedTextField(
                    value = newPlaylistName.orEmpty(),
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column {
                    Text(
                        text = "+ Новый плейлист",
                        color = NamiColors.Shu,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { newPlaylistName = "" }
                            .padding(vertical = 12.dp),
                    )
                    LazyColumn {
                        items(count = playlists.itemCount, key = playlists.itemKey { it.id.value }) { index ->
                            playlists[index]?.let { playlist ->
                                Text(
                                    text = playlist.name,
                                    color = NamiColors.Paper100,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.addToExistingPlaylist(playlist.id, trackIds)
                                            onDismiss()
                                        }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (newPlaylistName != null) {
                TextButton(onClick = {
                    val name = newPlaylistName.orEmpty()
                    if (name.isNotBlank()) {
                        viewModel.addToNewPlaylist(name, trackIds)
                        onDismiss()
                    }
                }) { Text("Создать") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
