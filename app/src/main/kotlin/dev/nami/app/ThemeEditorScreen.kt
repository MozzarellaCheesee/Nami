package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

/** П.md §26 "Редактор темы" - только цвет (см. Color.kt doc для остального из плана). Живой
 * предпросмотр = живое всё приложение, не изолированная миниатюра - оверрайд глобальный
 * (NamiColors) и применяется мгновенно на каждый ввод, план хотел отдельную превью-миниатюру,
 * но раз тема и так глобальный singleton, честнее и меньше кода - показывать реальный результат
 * сразу, а не строить параллельный мини-макет только для превью. */
@Composable
fun ThemeEditorScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val overrides by viewModel.themeColorOverrides.collectAsState()

    SettingsSubScreenScaffold(title = "Редактор темы", onBack = onBack) {
        Text(
            "Меняется сразу по всему приложению. Крестик у поля - сбросить это поле, кнопка снизу - сбросить всё.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            NamiColors.EDITABLE_TOKENS.forEachIndexed { index, token ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(color = NamiColors.Ink700)
                ThemeTokenRow(
                    token = token,
                    hex = overrides[token],
                    onHexChange = { newHex -> viewModel.setThemeColorOverride(token, newHex) },
                )
            }
        }
        Text(
            text = "Сбросить всё",
            color = NamiColors.Shu,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .clickable { viewModel.resetThemeColors() },
        )
    }
}

@Composable
private fun ThemeTokenRow(token: String, hex: String?, onHexChange: (String?) -> Unit) {
    val default = NamiColors.defaultOf(token)
    var text by remember(token, hex) { mutableStateOf(hex ?: colorToHex(default)) }
    val parsed = remember(text) { parseHexOrNull(text) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.size(36.dp)) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(36.dp).background(parsed ?: default, CircleShape),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(NamiColors.tokenLabel(token), color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    parseHexOrNull(newText)?.let { onHexChange(colorToHex(it)) }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        if (hex != null) {
            IconButton(onClick = { onHexChange(null) }) {
                Icon(Icons.Outlined.Close, contentDescription = "Сбросить (${colorToHex(default)})", tint = NamiColors.Paper40)
            }
        }
    }
}

private fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

private fun parseHexOrNull(text: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(text.trim()))
}.getOrNull()
