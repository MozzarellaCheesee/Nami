package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.model.TrackId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** А5 - сводный экран "всё, что известно о треке", каждое редактируемое поле открывает свой
 * диалог и сохраняет сразу же (см. TrackInfoViewModel). Поля без источника правки (формат,
 * битрейт, размер, даты) - только для чтения. */
@Composable
fun TrackInfoScreen(onBack: () -> Unit, viewModel: TrackInfoViewModel = hiltViewModel()) {
    val track by viewModel.track.collectAsState()
    val album by viewModel.album.collectAsState()

    var editField by remember { mutableStateOf<TrackInfoField?>(null) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    val trackTags by viewModel.trackTags.collectAsState()
    val allTags by viewModel.allTags.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Информация о треке", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        val current = track
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            return@Column
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            InfoSection(title = "Основное") {
                InfoRow("Название", current.title, onClick = { editField = TrackInfoField.Title })
                InfoRow("Исполнитель", current.artistName ?: "—", onClick = { editField = TrackInfoField.Artist })
                InfoRow("Альбом", album?.title ?: "—", onClick = { editField = TrackInfoField.Album })
                InfoRow("Год", album?.year?.toString() ?: "—", onClick = if (album != null) ({ editField = TrackInfoField.Year }) else null)
                InfoRow("Жанр", current.genre ?: "—", onClick = { editField = TrackInfoField.Genre })
                InfoRow("Заметка", current.note ?: "—", onClick = { editField = TrackInfoField.Note })
                RatingRow(current.rating, onRate = viewModel::setRating)
            }
            TagsSection(
                trackTags = trackTags,
                onRemove = viewModel::removeTag,
                onAddClick = { showAddTagDialog = true },
            )
            InfoSection(title = "Файл") {
                InfoRow("Формат", current.format)
                InfoRow("Длительность", formatDuration(current.durationMs))
                InfoRow("Трек / диск", "${current.trackNo ?: "—"} / ${current.discNo ?: "—"}")
                InfoRow("Частота дискретизации", current.sampleRateHz?.let { "$it Гц" } ?: "—")
                InfoRow("Разрядность", current.bitDepth?.let { "$it бит" } ?: "—")
                InfoRow("Каналы", current.channels?.toString() ?: "—")
                InfoRow("Размер", formatFileSize(current.sizeBytes))
                InfoRow("Путь", current.path)
            }
            InfoSection(title = "Прослушивание") {
                InfoRow("Добавлен", formatDate(current.dateAdded))
                InfoRow("Последний раз слушал", current.lastPlayed?.let { formatDate(it) } ?: "—")
                InfoRow("Прослушиваний", current.playCount.toString())
                InfoRow("Пропусков", current.skipCount.toString())
                InfoRow("BPM", current.bpm?.let { "%.0f".format(it) } ?: "—")
                InfoRow("Тональность", current.musicalKey ?: "—")
                InfoRow("ReplayGain", current.replayGainDb?.let { "%.1f дБ".format(it) } ?: "—")
            }
            Box(modifier = Modifier.padding(vertical = 20.dp))
        }
    }

    when (editField) {
        TrackInfoField.Title -> TextEditDialog("Название", track?.title.orEmpty(), onSave = viewModel::renameTrack, onDismiss = { editField = null })
        TrackInfoField.Artist -> TextEditDialog("Исполнитель", track?.artistName.orEmpty(), onSave = viewModel::setArtist, onDismiss = { editField = null })
        TrackInfoField.Album -> TextEditDialog("Альбом", album?.title.orEmpty(), onSave = viewModel::setAlbum, onDismiss = { editField = null })
        TrackInfoField.Year -> TextEditDialog("Год", album?.year?.toString().orEmpty(), isNumeric = true, onSave = { it.toIntOrNull()?.let(viewModel::setYear) }, onDismiss = { editField = null })
        TrackInfoField.Genre -> TextEditDialog("Жанр", track?.genre.orEmpty(), onSave = viewModel::setGenre, onDismiss = { editField = null })
        TrackInfoField.Note -> TextEditDialog("Заметка", track?.note.orEmpty(), multiline = true, onSave = { viewModel.setNote(it.ifBlank { null }) }, onDismiss = { editField = null })
        null -> Unit
    }
    if (showAddTagDialog) {
        AddTagDialog(
            existingTags = allTags.filterNot { tag -> trackTags.any { it.id == tag.id } },
            onPickExisting = { tagId -> viewModel.assignTag(tagId); showAddTagDialog = false },
            onCreate = { name, color -> viewModel.createAndAssignTag(name, color); showAddTagDialog = false },
            onDismiss = { showAddTagDialog = false },
        )
    }
}

private enum class TrackInfoField { Title, Artist, Album, Year, Genre, Note }

/** П.md §3's Tag/TrackTag - several color-coded tags per track, separate from the single genre
 * string. Removable chips + one "+" that opens [AddTagDialog]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(trackTags: List<dev.nami.domain.Tag>, onRemove: (dev.nami.domain.TagId) -> Unit, onAddClick: () -> Unit) {
    Text(
        "Теги",
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        trackTags.forEach { tag ->
            Row(
                modifier = Modifier
                    .background(androidx.compose.ui.graphics.Color(tag.colorArgb).copy(alpha = 0.2f), RoundedCornerShape(NamiRadius.Card))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tag.name, color = androidx.compose.ui.graphics.Color(tag.colorArgb), style = MaterialTheme.typography.bodySmall)
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Убрать тег",
                    tint = androidx.compose.ui.graphics.Color(tag.colorArgb),
                    modifier = Modifier.padding(start = 6.dp).size(14.dp).clickable { onRemove(tag.id) },
                )
            }
        }
        Row(
            modifier = Modifier
                .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
                .clickable(onClick = onAddClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.size(14.dp))
            Text("Тег", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun AddTagDialog(
    existingTags: List<dev.nami.domain.Tag>,
    onPickExisting: (dev.nami.domain.TagId) -> Unit,
    onCreate: (name: String, colorArgb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val swatches = listOf(
        NamiColors.Shu.toArgb(), NamiColors.Ai.toArgb(), NamiColors.Kin.toArgb(), NamiColors.Wakaba.toArgb(),
    )
    var selectedColor by remember { mutableStateOf(swatches.first()) }

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить тег") },
        text = {
            Column {
                if (existingTags.isNotEmpty()) {
                    Text("Существующие", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
                    existingTags.forEach { tag ->
                        Text(
                            tag.name,
                            color = androidx.compose.ui.graphics.Color(tag.colorArgb),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPickExisting(tag.id) }.padding(vertical = 8.dp),
                        )
                    }
                    Text("Новый", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Название тега") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    swatches.forEach { colorArgb ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(androidx.compose.ui.graphics.Color(colorArgb), androidx.compose.foundation.shape.CircleShape)
                                .then(
                                    if (colorArgb == selectedColor) {
                                        Modifier.border(2.dp, NamiColors.Paper100, androidx.compose.foundation.shape.CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { selectedColor = colorArgb },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim(), selectedColor) }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun InfoSection(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        color = NamiColors.Paper40,
        style = dev.nami.core.designsystem.NamiType.Caption,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
    Column(modifier = Modifier.fillMaxWidth().background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))) {
        content()
    }
}

@Composable
internal fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Подпись поля тише значения: раньше они были одного веса и цвета, и строка читалась
        // как сплошной текст, а не как "поле - значение".
        Text(label, color = NamiColors.Paper40, style = dev.nami.core.designsystem.NamiType.Secondary, modifier = Modifier.weight(1f))
        Text(
            value,
            color = NamiColors.Paper100,
            style = dev.nami.core.designsystem.NamiType.ListTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f),
        )
        if (onClick != null) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** П.md §3's Track.rating - 5 tappable stars, tapping the currently-set star clears it. */
@Composable
private fun RatingRow(rating: Int?, onRate: (Int?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Оценка", color = NamiColors.Paper70, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row {
            for (star in 1..5) {
                Icon(
                    if (rating != null && star <= rating) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (rating != null && star <= rating) NamiColors.Kin else NamiColors.Paper40,
                    modifier = Modifier.size(20.dp).clickable { onRate(if (star == rating) null else star) },
                )
            }
        }
    }
}

@Composable
internal fun TextEditDialog(
    label: String,
    initial: String,
    isNumeric: Boolean = false,
    multiline: Boolean = false,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = if (isNumeric) it.filter { c -> c.isDigit() }.take(4) else it },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim()); onDismiss() }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f МБ".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f КБ".format(bytes / 1_000.0)
    else -> "$bytes Б"
}

internal fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(epochMs))

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
