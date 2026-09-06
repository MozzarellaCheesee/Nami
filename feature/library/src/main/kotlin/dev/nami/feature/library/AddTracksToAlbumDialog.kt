package dev.nami.feature.library

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.model.AlbumId

@Composable
fun AddTracksToAlbumDialog(
    albumId: AlbumId,
    onDismiss: () -> Unit,
    viewModel: AddTracksToAlbumViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить треки в альбом") },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                    val track = tracks[index] ?: return@items
                    // Already in this album -- adding it again would be a no-op, hide it instead
                    // of leaving a dead tap in the list.
                    if (track.albumId == albumId) return@items
                    TrackListItem(track = track, onClick = { viewModel.addTrack(track.id, albumId) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
