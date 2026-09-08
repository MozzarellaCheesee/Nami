package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId

/** An album can be credited to more than one artist (compilations, splits, features) - this
 * lists everyone currently credited, with a remove button each, plus a way to add more (opens
 * [PickArtistDialog] on top, so several can be added one after another without reopening this). */
@Composable
fun ManageAlbumArtistsDialog(
    artists: List<Artist>,
    onAdd: (ArtistId) -> Unit,
    onRemove: (ArtistId) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPickArtist by remember { mutableStateOf(false) }

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Артисты альбома") },
        text = {
            LazyColumn {
                items(artists, key = { it.id.value }) { artist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = artist.name, color = NamiColors.Paper100, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(artist.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Убрать артиста", tint = NamiColors.Paper40)
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.Button)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showPickArtist = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = NamiColors.Paper100)
                            Text(text = "Добавить артиста", color = NamiColors.Paper100, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )

    if (showPickArtist) {
        PickArtistDialog(
            onPick = { artistId ->
                onAdd(artistId)
                showPickArtist = false
            },
            onDismiss = { showPickArtist = false },
        )
    }
}
