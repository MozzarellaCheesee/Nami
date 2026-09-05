package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.TrackId

@Composable
fun LibraryScreen(
    onTrackClick: (TrackId) -> Unit,
    onImportRequested: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900),
    ) {
        if (tracks.itemCount == 0) {
            Column(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    text = "Добавьте музыку с компьютера",
                    color = NamiColors.Paper70,
                )
            }
        } else {
            LazyColumn {
                items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                    tracks[index]?.let { track ->
                        TrackListItem(track = track, onClick = { onTrackClick(track.id) })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onImportRequested,
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Импортировать файлы")
        }
    }
}
