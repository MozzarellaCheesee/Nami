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
import androidx.compose.material3.Switch
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

/** Album counterpart of TrackInfoScreen -- same shared InfoSection/InfoRow/TextEditDialog. */
@Composable
fun AlbumInfoScreen(onBack: () -> Unit, viewModel: AlbumInfoViewModel = hiltViewModel()) {
    val album by viewModel.album.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val stats by viewModel.stats.collectAsState()

    var editField by remember { mutableStateOf<AlbumInfoField?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Информация об альбоме", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        val current = album
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            return@Column
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            InfoSection(title = "Основное") {
                InfoRow("Название", current.title, onClick = { editField = AlbumInfoField.Title })
                InfoRow("Артисты", artists.joinToString(", ") { it.name }.ifEmpty { "—" })
                InfoRow("Год", current.year?.toString() ?: "—", onClick = { editField = AlbumInfoField.Year })
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Сингл", color = NamiColors.Paper70, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = current.isSingle, onCheckedChange = viewModel::setIsSingle)
                }
            }
            InfoSection(title = "Содержимое") {
                InfoRow("Треков", stats.trackCount.toString())
                InfoRow("Общая длительность", formatDuration(stats.totalDurationMs))
                InfoRow("Обложка", if (current.artworkPath != null) "есть" else "нет")
            }
            Box(modifier = Modifier.padding(vertical = 20.dp))
        }
    }

    when (editField) {
        AlbumInfoField.Title -> TextEditDialog("Название", album?.title.orEmpty(), onSave = viewModel::rename, onDismiss = { editField = null })
        AlbumInfoField.Year -> TextEditDialog(
            "Год",
            album?.year?.toString().orEmpty(),
            isNumeric = true,
            onSave = { viewModel.setYear(it.toIntOrNull()) },
            onDismiss = { editField = null },
        )
        null -> Unit
    }
}

private enum class AlbumInfoField { Title, Year }
