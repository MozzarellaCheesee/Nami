package dev.nami.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

/**
 * Шрифт, лежащий прямо в APK (app/src/main/assets/fonts). Всё - статический Regular, только
 * latin+cyrillic, по ~60 КБ на файл: набор должен выбираться одним тапом, а не раздувать сборку.
 *
 * ponytail: только Regular. Жирное начертание Compose синтезирует сам; если где-то это будет
 * заметно резать глаз - добавить рядом *-Bold.ttf и грузить FontFamily из двух Font с весами
 * (тогда придётся хранить не путь к одному файлу, а имя набора).
 */
data class BundledFont(val title: String, val key: String) {
    val assetPath: String get() = "fonts/$key.ttf"
}

/** Лицензии: Roboto - Apache 2.0, остальные - SIL OFL 1.1, см. assets/fonts/LICENSES.txt. */
val BUNDLED_FONTS = listOf(
    BundledFont("Inter", "inter"),
    BundledFont("Roboto", "roboto"),
    BundledFont("Montserrat", "montserrat"),
    BundledFont("Manrope", "manrope"),
    BundledFont("Nunito", "nunito"),
    BundledFont("Lora", "lora"),
    BundledFont("JetBrains Mono", "jetbrainsmono"),
)

/**
 * Шрифты для иероглифов (CJK). Полный Noto Sans CJK - это 8-16 МБ на язык в одном начертании,
 * поэтому здесь подмножества: инстанцированный wght=400 плюс обрезка по частотному разбиению
 * самого Google Fonts (их css2 отдаёт шрифт срезами, индекс 0 - самые частые символы). Взят
 * префикс срезов до ~3000 иероглифов для SC/TC, ~2600 кандзи + вся кана для JP, ~2600 слогов
 * для KR - это покрывает обычный текст песен и интерфейс; редкий иероглиф вне подмножества
 * дорисует системный fallback, как и раньше. Скрипт сборки описан в LICENSES.txt.
 */
val CJK_FONTS = listOf(
    BundledFont("Noto Sans SC - упрощённый китайский", "notosanssc"),
    BundledFont("Noto Sans TC - традиционный китайский", "notosanstc"),
    BundledFont("Noto Sans JP - японский", "notosansjp"),
    BundledFont("Noto Sans KR - корейский", "notosanskr"),
)

/** Путь, по которому оседает выбранный встроенный шрифт - по нему же понятно, какой именно
 * выбран сейчас (свой файл кладётся рядом под именем без ключа). */
fun bundledFontFileName(key: String, prefix: String) = "${prefix}_$key.ttf"

/** Образец текста на своём языке для превью CJK-шрифта - латинская фраза тут бессмысленна. */
private fun cjkPreviewText(key: String): String = when (key) {
    "notosanssc" -> "吃葡萄不吐葡萄皮"
    "notosanstc" -> "吃葡萄不吐葡萄皮"
    "notosansjp" -> "隣の客はよく柿食う客だ"
    "notosanskr" -> "저기 있는 저 콩깍지가"
    else -> key
}

@Composable
private fun SettingsSubSectionLabel(text: String) {
    Text(
        text = text,
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * Экран выбора шрифта: сверху встроенные (каждый показан своей же гарнитурой), снизу старые пути -
 * свой файл из системы и сброс на стандартный. Один и тот же экран используется и для интерфейса,
 * и для лирики - разница только в том, какая настройка меняется.
 */
@Composable
internal fun FontPickerScreen(
    title: String,
    currentPath: String?,
    prefix: String,
    onPickBundled: (BundledFont) -> Unit,
    onPickCustomFile: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    // Отдельный набор под иероглифы (CJK) - своя настройка (uiCjkFontPath/lyricsCjkFontPath),
    // подмешивается к выбранному выше как fallback по покрытию символов, см. customFontFamily.
    // "Другое" (свой файл/сброс) сюда не имеет смысла - у обычных .ttf/.otf с латиницей почти
    // никогда нет иероглифов, а если есть - его можно выбрать и как основной набор выше.
    cjkCurrentPath: String? = null,
    cjkPrefix: String? = null,
    onPickCjkBundled: ((BundledFont) -> Unit)? = null,
    onResetCjk: (() -> Unit)? = null,
) {
    val assets = LocalContext.current.assets
    SettingsSubScreenScaffold(title = title, onBack = onBack) {
        SettingsSubSectionLabel("Встроенные")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            BUNDLED_FONTS.forEach { font ->
                val selected = currentPath?.endsWith(bundledFontFileName(font.key, prefix)) == true
                // Превью названия самой гарнитурой - remember по ключу, потому что Font(asset)
                // это реальный разбор файла, а не константа.
                val family = remember(font.key) { FontFamily(Font(font.assetPath, assets)) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickBundled(font) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = font.title,
                            color = NamiColors.Paper100,
                            fontFamily = family,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Съешь ещё этих мягких булок - 0123",
                            color = NamiColors.Paper40,
                            fontFamily = family,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = "Выбран", tint = NamiColors.Shu)
                    }
                }
            }
        }
        if (cjkPrefix != null && onPickCjkBundled != null && onResetCjk != null) {
            SettingsSubSectionLabel("Иероглифы (CJK)")
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                CJK_FONTS.forEach { font ->
                    val selected = cjkCurrentPath?.endsWith(bundledFontFileName(font.key, cjkPrefix)) == true
                    val family = remember(font.key) { FontFamily(Font(font.assetPath, assets)) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickCjkBundled(font) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(font.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                            // Превью настоящим текстом на этом языке, той же гарнитурой - не
                            // латиницей: смысл превью для CJK-шрифта именно в иероглифах.
                            Text(cjkPreviewText(font.key), color = NamiColors.Paper40, fontFamily = family, style = MaterialTheme.typography.titleMedium)
                        }
                        if (selected) {
                            Icon(Icons.Outlined.Check, contentDescription = "Выбран", tint = NamiColors.Shu)
                        }
                    }
                }
                if (cjkCurrentPath != null) {
                    SettingsRow(
                        icon = Icons.Outlined.Close,
                        title = "Не использовать отдельный шрифт для иероглифов",
                        subtitle = "Иероглифы снова рисует системный шрифт",
                        trailing = {},
                        onClick = onResetCjk,
                    )
                }
            }
        }
        SettingsSubSectionLabel("Другое")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.FolderOpen,
                title = "Выбрать свой файл",
                subtitle = ".ttf или .otf из памяти устройства",
                trailing = {},
                onClick = onPickCustomFile,
            )
            if (currentPath != null) {
                SettingsRow(
                    icon = Icons.Outlined.Close,
                    title = "Сбросить на стандартный",
                    trailing = {},
                    onClick = onReset,
                )
            }
        }
    }
}

@Composable
fun UiFontScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiFontPath by viewModel.uiFontPath.collectAsState()
    val uiCjkFontPath by viewModel.uiCjkFontPath.collectAsState()
    val pickCustom = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::pickUiFont) }
    FontPickerScreen(
        title = "Шрифт интерфейса",
        currentPath = uiFontPath,
        prefix = "ui",
        onPickBundled = viewModel::pickBundledUiFont,
        onPickCustomFile = { pickCustom.launch(arrayOf("font/ttf", "font/otf", "*/*")) },
        onReset = viewModel::clearUiFont,
        onBack = onBack,
        cjkCurrentPath = uiCjkFontPath,
        cjkPrefix = "uicjk",
        onPickCjkBundled = viewModel::pickBundledUiCjkFont,
        onResetCjk = viewModel::clearUiCjkFont,
    )
}

@Composable
fun LyricsFontScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val lyricsFontPath by viewModel.lyricsFontPath.collectAsState()
    val lyricsCjkFontPath by viewModel.lyricsCjkFontPath.collectAsState()
    val pickCustom = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::pickLyricsFont) }
    FontPickerScreen(
        title = "Шрифт текста песни",
        currentPath = lyricsFontPath,
        prefix = "lyrics",
        onPickBundled = viewModel::pickBundledLyricsFont,
        onPickCustomFile = { pickCustom.launch(arrayOf("font/ttf", "font/otf", "*/*")) },
        onReset = viewModel::clearLyricsFont,
        onBack = onBack,
        cjkCurrentPath = lyricsCjkFontPath,
        cjkPrefix = "lyricscjk",
        onPickCjkBundled = viewModel::pickBundledLyricsCjkFont,
        onResetCjk = viewModel::clearLyricsCjkFont,
    )
}

/** Подпись справа в строке настройки: имя встроенного набора, "Свой" для файла из системы. */
internal fun uiFontLabel(path: String?): String {
    if (path == null) return "Стандартный"
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    return BUNDLED_FONTS.firstOrNull { name.endsWith("_${it.key}.ttf") }?.title ?: "Свой"
}
