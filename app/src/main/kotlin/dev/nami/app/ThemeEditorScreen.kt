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
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
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
import dev.nami.core.designsystem.NamiTypeScale

/** П.md §26 "Редактор темы" - галерея пресетов, цвет, форма, плотность, масштаб текста,
 * выключатель размытия, автоночник и экспорт/импорт .json. Не сделано из плана: режимы акцента,
 * проверка контраста, пипетка с обложки и история цветов, импорт по ссылке и по QR, остальные
 * правила автопереключения (системная тема, устройство вывода, плейлист) - каждое отдельная
 * задача. Гарнитура настраивается не здесь, а во Внешнем виде (uiFontPath).
 *
 * Живой предпросмотр = живое всё приложение, не изолированная миниатюра - оверрайд глобальный
 * (NamiColors) и применяется мгновенно на каждый ввод, план хотел отдельную превью-миниатюру,
 * но раз тема и так глобальный singleton, честнее и меньше кода - показывать реальный результат
 * сразу, а не строить параллельный мини-макет только для превью. */
@Composable
fun ThemeEditorScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val overrides by viewModel.themeColorOverrides.collectAsState()
    val shapeOverrides by viewModel.themeShapeOverrides.collectAsState()
    val densityScale by viewModel.themeDensityScale.collectAsState()
    val fontScale by viewModel.themeFontScale.collectAsState()
    val blurEnabled by viewModel.blurEnabled.collectAsState()
    val autoNightAmoled by viewModel.autoNightAmoled.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val exportTheme = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTheme) }
    val importTheme = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importTheme) }
    val ioMessage by viewModel.themeIoMessage.collectAsState()
    androidx.compose.runtime.LaunchedEffect(ioMessage) {
        val message = ioMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.themeIoMessageShown()
    }

    SettingsSubScreenScaffold(title = "Редактор темы", onBack = onBack) {
        Text(
            "Меняется сразу по всему приложению. Крестик у поля - сбросить это поле, кнопка снизу - сбросить всё.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        SectionCaption("Галерея")
        // Пресеты "Из обоев"/"Из обложки" считаются в рантайме (система/Palette), поэтому
        // подмешиваются к статическому списку здесь, а не лежат в THEME_PRESETS константами.
        val artworkPath by viewModel.nowPlayingArtworkPath.collectAsState()
        val artworkAccent = dev.nami.feature.player.rememberArtworkAccentColor(artworkPath)
        val presets = THEME_PRESETS +
            listOfNotNull(wallpaperPreset(context)) +
            listOfNotNull(artworkPath?.let { artworkPreset(artworkAccent.toArgb()) })
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            presets.forEachIndexed { index, preset ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(color = NamiColors.Ink700)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.applyThemePreset(preset) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Превью - три кружка из самого пресета (фон/поверхность/акцент), а не
                    // отрисованная мини-карточка: этого хватает, чтобы отличить пресеты глазом.
                    listOf(NamiColors.TOKEN_INK900, NamiColors.TOKEN_INK800, NamiColors.TOKEN_SHU).forEach { token ->
                        val color = preset.colors[token]?.let(::parseHexOrNull) ?: NamiColors.defaultOf(token)
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.size(20.dp).padding(end = 4.dp).background(color, CircleShape),
                        )
                    }
                    Text(
                        preset.name,
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Text(
            "Тап применяет пресет и снимает все ручные правки цвета. Пресет - это набор цветов " +
                "поверх тёмной схемы, поэтому на светлой \"Бумаге\" отдельные захардкоженные " +
                "затемнения (например ночной режим в плеере) остаются тёмными.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
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
        SectionCaption("Контраст")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            CONTRAST_PAIRS.forEachIndexed { index, (fg, bg) ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(color = NamiColors.Ink700)
                ContrastRow(fg, bg, overrides)
            }
        }
        Text(
            "Коэффициент по WCAG 2.1, порог для обычного текста - 4.5:1. Это подсказка, а не " +
                "запрет: низкий контраст может быть осознанным решением, поэтому применить " +
                "цвет всё равно можно.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
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
            ScalePills(NamiDensity.SCALES, densityScale, viewModel::setThemeDensityScale)
        }
        Text(
            "Пока меняет только высоту строки в списках треков - остальные отступы приложения фиксированные.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SectionCaption("Типографика")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScalePills(NamiTypeScale.SCALES, fontScale, viewModel::setThemeFontScale)
        }
        Text(
            "Масштаб текста поверх системного - системная настройка размера шрифта продолжает " +
                "работать сверх этой. Гарнитура настраивается отдельно, во Внешнем виде.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SectionCaption("Прозрачность")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.BlurOn,
                title = "Размытие фонов",
                trailing = { NamiSwitch(checked = blurEnabled, onCheckedChange = viewModel::setBlurEnabled) },
                onClick = { viewModel.setBlurEnabled(!blurEnabled) },
            )
        }
        Text(
            "Выключи на слабом устройстве: размытые обложки в плеере, очереди, тексте и " +
                "мини-плеере рисуются на GPU и стоят дороже всего остального в кадре.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SectionCaption("Автопереключение")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.Bedtime,
                title = "Ночник после 23:00",
                trailing = { NamiSwitch(checked = autoNightAmoled, onCheckedChange = viewModel::setAutoNightAmoled) },
                onClick = { viewModel.setAutoNightAmoled(!autoNightAmoled) },
            )
        }
        Text(
            "С 23:00 до 6:00 включает AMOLED-чёрный сам. Час проверяется при открытии " +
                "приложения, а не живым таймером - если приложение уже открыто, тема сменится " +
                "на следующем запуске.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SectionCaption("Файл темы")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.FileUpload,
                title = "Экспортировать в .json",
                trailing = {},
                onClick = { exportTheme.launch("nami-theme.json") },
            )
            androidx.compose.material3.HorizontalDivider(color = NamiColors.Ink700)
            SettingsRow(
                icon = Icons.Outlined.FileDownload,
                title = "Импортировать .json",
                trailing = {},
                // */* вторым: часть файловых менеджеров отдаёт .json как text/plain или
                // application/octet-stream, и по одному только application/json файл не выбрать.
                onClick = { importTheme.launch(arrayOf("application/json", "*/*")) },
            )
        }
        Text(
            "Импорт заменяет цвета, форму, плотность и масштаб текста целиком. Неизвестные поля " +
                "и некорректные значения в чужом файле игнорируются, а не ломают импорт.",
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

/** Ряд пилюль "выбери один множитель" - одинаковый для плотности и масштаба текста. */
@Composable
private fun ScalePills(scales: List<Pair<Float, String>>, current: Float, onSelect: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        scales.forEach { (value, label) ->
            val selected = kotlin.math.abs(current - value) < 0.01f
            Text(
                text = label,
                color = if (selected) NamiColors.Ink900 else NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .background(if (selected) NamiColors.Shu else NamiColors.Ink700, RoundedCornerShape(NamiRadius.Button))
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
            )
        }
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

/** Пары "текст на фоне", которые реально встречаются в интерфейсе. Проверять все 12x12
 * сочетаний незачем: Shu на Kin нигде не рисуется, а список из 144 строк никто не читает. */
private val CONTRAST_PAIRS = listOf(
    NamiColors.TOKEN_PAPER100 to NamiColors.TOKEN_INK900,
    NamiColors.TOKEN_PAPER70 to NamiColors.TOKEN_INK900,
    NamiColors.TOKEN_PAPER40 to NamiColors.TOKEN_INK900,
    NamiColors.TOKEN_PAPER100 to NamiColors.TOKEN_INK800,
    NamiColors.TOKEN_PAPER70 to NamiColors.TOKEN_INK800,
    NamiColors.TOKEN_SHU to NamiColors.TOKEN_INK900,
    NamiColors.TOKEN_AI to NamiColors.TOKEN_INK900,
    NamiColors.TOKEN_KIN to NamiColors.TOKEN_INK900,
    NamiColors.TOKEN_WAKABA to NamiColors.TOKEN_INK900,
)

@Composable
private fun ContrastRow(fgToken: String, bgToken: String, overrides: Map<String, String>) {
    fun colorOf(token: String) =
        overrides[token]?.let(::parseHexOrNull) ?: NamiColors.defaultOf(token)
    val fg = colorOf(fgToken)
    val bg = colorOf(bgToken)
    val ratio = contrastRatio(fg.toArgb(), bg.toArgb())
    val passes = ratio >= 4.5
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Образец рисуется теми же цветами, что и оценивается - число рядом с тем, что видно.
        Text(
            "Aa",
            color = fg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .background(bg, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            "${NamiColors.tokenLabel(fgToken)} на ${NamiColors.tokenLabel(bgToken)}",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Text(
            "%.1f:1".format(ratio) + if (passes) "" else " ⚠",
            color = if (passes) NamiColors.Paper70 else NamiColors.Shu,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

private fun parseHexOrNull(text: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(text.trim()))
}.getOrNull()
