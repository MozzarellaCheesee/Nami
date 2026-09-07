package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Warning
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
import dev.nami.domain.HealthTrackRef
import dev.nami.domain.LibraryHealthReport

/** П.md §23.18 "Здоровье библиотеки" -- one card per diagnosed category, tap to expand and see
 * the actual tracks, with a real fix action where one exists (missing files / duplicates). No
 * "фейковый hi-res" category -- see LibraryHealthReport's own doc for why. */
@Composable
fun LibraryHealthScreen(onBack: () -> Unit, viewModel: LibraryHealthViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = "Здоровье библиотеки", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        if (isLoading && report == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            return@Column
        }
        val current = report ?: return@Column

        LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
            item {
                HealthCategory(
                    title = "Без обложки",
                    count = current.tracksWithoutArtwork.size,
                    items = current.tracksWithoutArtwork.map { it.title },
                )
            }
            item {
                HealthCategory(
                    title = "Без текста песни",
                    count = current.tracksWithoutLyrics.size,
                    items = current.tracksWithoutLyrics.map { it.title },
                )
            }
            item {
                HealthCategory(
                    title = "Альбомы без года выпуска",
                    count = current.albumsWithoutYear.size,
                    items = current.albumsWithoutYear,
                )
            }
            item {
                HealthCategory(
                    title = "Несогласованные имена артистов",
                    count = current.inconsistentArtistNameGroups.size,
                    items = current.inconsistentArtistNameGroups.map { it.joinToString(" / ") },
                )
            }
            item {
                HealthCategory(
                    title = "Отсутствующие файлы",
                    count = current.missingFiles.size,
                    items = current.missingFiles.map { it.title },
                    onDeleteItem = { index -> viewModel.deleteTrack(current.missingFiles[index].id) },
                )
            }
            item {
                DuplicatesCategory(current.duplicateGroups, onResolve = { keep, group -> viewModel.resolveDuplicateGroup(keep, group.map { it.id }) })
            }
        }
    }
}

@Composable
private fun HealthCategory(title: String, count: Int, items: List<String>, onDeleteItem: ((Int) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 8.dp).background(NamiColors.Ink800, RoundedCornerShape(12.dp)).clickable(enabled = count > 0) { expanded = !expanded }) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (count == 0) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (count == 0) NamiColors.Paper40 else NamiColors.Kin,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                Text("$count", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded) {
            items.forEachIndexed { index, label ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (onDeleteItem != null) {
                        IconButton(onClick = { onDeleteItem(index) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = NamiColors.Paper40)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicatesCategory(groups: List<List<HealthTrackRef>>, onResolve: (keepId: dev.nami.core.model.TrackId, group: List<HealthTrackRef>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 8.dp).background(NamiColors.Ink800, RoundedCornerShape(12.dp)).clickable(enabled = groups.isNotEmpty()) { expanded = !expanded }) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (groups.isEmpty()) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (groups.isEmpty()) NamiColors.Paper40 else NamiColors.Kin,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Похожие дубликаты", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                Text("${groups.size} групп", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded) {
            groups.forEach { group ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    group.forEach { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(ref.title, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                "Оставить это",
                                color = NamiColors.Shu,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { onResolve(ref.id, group) },
                            )
                        }
                    }
                }
            }
        }
    }
}
