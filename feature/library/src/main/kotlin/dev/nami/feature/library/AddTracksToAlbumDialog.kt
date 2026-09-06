package dev.nami.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId

@Composable
fun AddTracksToAlbumDialog(
    albumId: AlbumId,
    onDismiss: () -> Unit,
    viewModel: AddTracksToAlbumViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить треки в альбом") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                    tracks[index]?.let { track ->
                        Text(
                            text = track.title,
                            color = NamiColors.Paper100,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.addTrack(track.id, albumId) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
