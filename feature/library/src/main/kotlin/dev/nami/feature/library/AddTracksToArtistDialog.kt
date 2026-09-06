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
import dev.nami.core.model.ArtistId

/** Existing library tracks only -- adding a brand new (not-yet-imported) file to a specific
 * artist isn't something the app can do without also picking where it lives on disk; that's just
 * a normal folder/file import (already tags the artist from its own metadata), not this dialog. */
@Composable
fun AddTracksToArtistDialog(
    artistId: ArtistId,
    onDismiss: () -> Unit,
    viewModel: AddTracksToArtistViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить треки артисту") },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                    val track = tracks[index] ?: return@items
                    // Already this artist's track -- adding it again would be a no-op.
                    if (track.artistId == artistId) return@items
                    TrackListItem(track = track, onClick = { viewModel.addTrack(track.id, artistId) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
