package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiDensity
import dev.nami.core.designsystem.NamiRadius

/** П.md §26 "Редактор темы" - цвет, форма и плотность (типографика настраивается отдельно
 * через свой шрифт интерфейса; прозрачность/blur, режимы акцента, автопереключение, проверка
 * контраста, экспорт/импорт .json и галерея тем из плана не сделаны - каждое отдельная задача).
 * Живой
 * предпросмотр = живое всё приложение, не изолированная миниатюра - оверрайд глобальный
 * (NamiColors) и применяется мгновенно на каждый ввод, план хотел отдельную превью-миниатюру,
 * но раз тема и так глобальный singleton, честнее и меньше кода - показывать реальный результат
 * сразу, а не строить параллельный мини-макет только для превью. */
@Composable
fun ThemeEditorScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val overrides by viewModel.themeColorOverrides.collectAsState()
    val shapeOverrides by viewModel.themeShapeOverrides.collectAsState()
    val densityScale by viewModel.themeDensityScale.collectAsState()

    SettingsSubScreenScaffold(title = "Редактор темы", onBack = onBack) {
        Text(
            "Меняется сразу по всему приложению. Крестик у поля - сбросить это поле, кнопка снизу - сбросить всё.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        SectionCaption("Цвет")
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
        SectionCaption("Форма")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            NamiRadius.EDITABLE_TOKENS.forEachIndexed { index, token ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(color = NamiColors.Ink700)
                ShapeTokenRow(
                    token = token,
                    dp = shapeOverrides[token],
                    onChange = { value -> viewModel.setThemeShapeOverride(token, value) },
                )
            }
        }

        SectionCaption("Плотность")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                NamiDensity.SCALES.forEach { (value, label) ->
                    val selected = kotlin.math.abs(densityScale - value) < 0.01f
                    Text(
                        text = label,
                        color = if (selected) NamiColors.Ink900 else NamiColors.Paper70,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .background(if (selected) NamiColors.Shu else NamiColors.Ink700, RoundedCornerShape(NamiRadius.Button))
                            .clickable { viewModel.setThemeDensityScale(value) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
        Text(
            "Пока меняет только высоту строки в списках треков - остальные отступы приложения фиксированные.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Text(
            text = "Сбросить всё",
            color = NamiColors.Shu,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .clickable {
                    viewModel.resetThemeColors()
                    viewModel.resetThemeShapeAndDensity()
                },
        )
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text,
        color = NamiColors.Paper70,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Ползунок радиуса. Шаг целый dp - дробные радиусы на глаз неотличимы, а целое значение
 * проще и хранить (JSON int), и показать пользователю. */
@Composable
private fun ShapeTokenRow(token: String, dp: Int?, onChange: (Int?) -> Unit) {
    val default = NamiRadius.defaultOf(token).value.toInt()
    val current = dp ?: default
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(NamiRadius.tokenLabel(token), color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$current dp", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
            if (dp != null) {
                IconButton(onClick = { onChange(null) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Сбросить ($default dp)", tint = NamiColors.Paper40)
                }
            }
        }
        Slider(
            value = current.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = NamiRadius.MIN_DP.toFloat()..NamiRadius.MAX_DP.toFloat(),
            steps = NamiRadius.MAX_DP - NamiRadius.MIN_DP - 1,
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
