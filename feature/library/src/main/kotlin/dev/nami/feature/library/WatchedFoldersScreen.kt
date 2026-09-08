package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

/** П.md §2 "Режим наблюдения за папкой" - SAF trees to keep re-scanning. No true background
 * watch exists for SAF trees on Android (see LibraryViewModel.rescanWatchedFolders's own doc) --
 * rescanned on cold start, plus a manual refresh here. */
@Composable
fun WatchedFoldersScreen(
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val folders by viewModel.watchedFolders.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Отслеживаемые папки", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "Пересканируются при каждом запуске приложения - фонового слежения за папками на Android нет.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        )
        if (folders.isEmpty()) {
            Text(
                "Пока ни одной папки не добавлено",
                color = NamiColors.Paper40,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            folders.forEach { uri ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = NamiColors.Paper70)
                    Text(
                        android.net.Uri.parse(uri).lastPathSegment ?: uri,
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    IconButton(onClick = { viewModel.removeWatchedFolder(uri) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Убрать", tint = NamiColors.Paper40)
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(20.dp)
                .background(NamiColors.Ink800, RoundedCornerShape(20.dp))
                .clickable(onClick = onAddFolder)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = NamiColors.Paper100, modifier = Modifier.size(18.dp))
                Text("Добавить папку", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
