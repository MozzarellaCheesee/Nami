package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

/** Artist counterpart of TrackInfoScreen/AlbumInfoScreen. */
@Composable
fun ArtistInfoScreen(onBack: () -> Unit, viewModel: ArtistInfoViewModel = hiltViewModel()) {
    val artist by viewModel.artist.collectAsState()
    val stats by viewModel.stats.collectAsState()

    var editingName by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Информация об артисте", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        val current = artist
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            return@Column
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            InfoSection(title = "Основное") {
                InfoRow("Имя", current.name, onClick = { editingName = true })
                InfoRow("Фото", if (current.photoPath != null) "есть" else "нет")
            }
            InfoSection(title = "Библиотека") {
                InfoRow("Альбомов", stats.albumCount.toString())
                InfoRow("Треков", stats.trackCount.toString())
                InfoRow("Общая длительность", formatDuration(stats.totalDurationMs))
            }
            Box(modifier = Modifier.padding(vertical = 20.dp))
        }
    }

    if (editingName) {
        TextEditDialog("Имя", artist?.name.orEmpty(), onSave = viewModel::rename, onDismiss = { editingName = false })
    }
}
