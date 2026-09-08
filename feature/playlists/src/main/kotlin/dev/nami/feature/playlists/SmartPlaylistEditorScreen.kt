package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.nami.domain.SmartField
import dev.nami.domain.SmartOperator
import dev.nami.domain.SmartRule
import dev.nami.domain.SmartSortField

private data class SmartPreset(val label: String, val rules: List<SmartRule>, val sortBy: SmartSortField, val sortDescending: Boolean, val limit: Int?)

private val PRESETS = listOf(
    SmartPreset("Ни разу не слушал", listOf(SmartRule(SmartField.PLAY_COUNT, SmartOperator.EQUALS, "0")), SmartSortField.DATE_ADDED, true, null),
    SmartPreset("Топ 100", emptyList(), SmartSortField.PLAY_COUNT, true, 100),
    SmartPreset("Забытое (полгода)", listOf(SmartRule(SmartField.LAST_PLAYED_DAYS_AGO, SmartOperator.GREATER_THAN, "180")), SmartSortField.DATE_ADDED, true, null),
    SmartPreset("Короткие (<2:30)", listOf(SmartRule(SmartField.DURATION_SEC, SmartOperator.LESS_THAN, "150")), SmartSortField.DURATION, false, null),
    SmartPreset("Только FLAC", listOf(SmartRule(SmartField.FORMAT, SmartOperator.EQUALS, "flac")), SmartSortField.DATE_ADDED, true, null),
    SmartPreset("Без лирики", listOf(SmartRule(SmartField.HAS_LYRICS, SmartOperator.EQUALS, "false")), SmartSortField.DATE_ADDED, true, null),
)

private fun fieldLabel(field: SmartField): String = when (field) {
    SmartField.GENRE -> "Жанр"
    SmartField.FORMAT -> "Формат"
    SmartField.PLAY_COUNT -> "Прослушиваний"
    SmartField.ADDED_DAYS_AGO -> "Дней с добавления"
    SmartField.LAST_PLAYED_DAYS_AGO -> "Дней с последнего прослушивания"
    SmartField.DURATION_SEC -> "Длительность (сек)"
    SmartField.HAS_LYRICS -> "Есть текст"
}

private fun operatorLabel(op: SmartOperator): String = when (op) {
    SmartOperator.EQUALS -> "="
    SmartOperator.NOT_EQUALS -> "≠"
    SmartOperator.GREATER_THAN -> ">"
    SmartOperator.LESS_THAN -> "<"
}

private fun sortLabel(field: SmartSortField): String = when (field) {
    SmartSortField.DATE_ADDED -> "Дата добавления"
    SmartSortField.TITLE -> "Название"
    SmartSortField.PLAY_COUNT -> "Прослушивания"
    SmartSortField.DURATION -> "Длительность"
}

/** П.md's `SmartPlaylistEditor(playlistId?)` route - "конструктор правил на чипах" from §20:
 * presets for the common cases, plus add-a-rule-at-a-time for anything else. Rules always AND
 * together (see SmartPlaylistEvaluator's own doc for why no OR combinator exists). */
@Composable
fun SmartPlaylistEditorScreen(onBack: () -> Unit, viewModel: SmartPlaylistEditorViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddRule by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().background(NamiColors.Ink900).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(text = "Умный плейлист", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::setName,
                placeholder = { Text("Название плейлиста") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Text(text = "Готовые наборы", color = NamiColors.Paper40, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                PRESETS.forEach { preset ->
                    Chip(preset.label) { viewModel.applyPreset(preset.rules, preset.sortBy, preset.sortDescending, preset.limit) }
                }
            }

            Text(text = "Правила (все должны совпасть)", color = NamiColors.Paper40, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            uiState.rules.forEachIndexed { index, rule ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(NamiColors.Ink800, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${fieldLabel(rule.field)} ${operatorLabel(rule.operator)} ${rule.value}",
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removeRule(index) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Убрать правило", tint = NamiColors.Paper40)
                    }
                }
            }
            Text(
                text = "+ Добавить правило",
                color = NamiColors.Shu,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).clickable { showAddRule = true },
            )

            Text(text = "Сортировка", color = NamiColors.Paper40, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SmartSortField.entries.forEach { field ->
                    Chip(sortLabel(field), selected = uiState.sortBy == field) { viewModel.setSortBy(field) }
                }
                Chip(if (uiState.sortDescending) "По убыванию" else "По возрастанию") { viewModel.setSortDescending(!uiState.sortDescending) }
            }

            TextButton(onClick = { viewModel.save(onBack) }, modifier = Modifier.padding(top = 24.dp)) {
                Text("Сохранить", color = NamiColors.Shu)
            }
        }
    }

    if (showAddRule) {
        AddRuleDialog(
            onAdd = { rule -> viewModel.addRule(rule); showAddRule = false },
            onDismiss = { showAddRule = false },
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) NamiColors.Paper100 else NamiColors.Ink800, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = if (selected) NamiColors.Ink900 else NamiColors.Paper70, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AddRuleDialog(onAdd: (SmartRule) -> Unit, onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(SmartField.GENRE) }
    var operator by remember { mutableStateOf(SmartOperator.EQUALS) }
    var value by remember { mutableStateOf("") }

    dev.nami.core.designsystem.NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое правило") },
        text = {
            Column {
                Text(text = "Поле", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    SmartField.entries.forEach { f -> Chip(fieldLabel(f), selected = field == f) { field = f } }
                }
                Text(text = "Оператор", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SmartOperator.entries.forEach { op -> Chip(operatorLabel(op), selected = operator == op) { operator = op } }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("Значение") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onAdd(SmartRule(field, operator, value.trim())) }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
