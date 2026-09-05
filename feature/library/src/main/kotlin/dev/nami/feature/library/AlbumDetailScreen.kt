package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
                .padding(20.dp),
        )
        Text(
            text = uiState.album?.title ?: "",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn {
            items(uiState.tracks, key = { it.id.value }) { track ->
                TrackListItem(track = track, onClick = { })
            }
        }
    }
}
