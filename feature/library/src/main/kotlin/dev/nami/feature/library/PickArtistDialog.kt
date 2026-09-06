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

/** Attach/reattach an album to an existing library artist -- picking from artists that already
 * exist (mirrors how AddTracksToArtistDialog treats new-file import as a separate concern). */
@Composable
fun PickArtistDialog(
    onPick: (ArtistId) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PickArtistViewModel = hiltViewModel(),
) {
    val artists = viewModel.artists.collectAsLazyPagingItems()

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбрать артиста") },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(count = artists.itemCount, key = artists.itemKey { it.id.value }) { index ->
                    val artist = artists[index] ?: return@items
                    ArtistListItem(artist = artist, onClick = { onPick(artist.id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
