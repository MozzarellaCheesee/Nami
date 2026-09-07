package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

@Composable
fun SettingsScreen(onTrashClick: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val autoOpenPlayer by viewModel.autoOpenPlayer.collectAsState()
    val hideSystemBars by viewModel.hideSystemBars.collectAsState()
    val karaokeEnabled by viewModel.karaokeEnabled.collectAsState()
    val studyModeEnabled by viewModel.studyModeEnabled.collectAsState()
    val lyricsFontPath by viewModel.lyricsFontPath.collectAsState()
    val eqEnabled by viewModel.eqEnabled.collectAsState()
    val eqBassDb by viewModel.eqBassDb.collectAsState()
    val eqMidDb by viewModel.eqMidDb.collectAsState()
    val eqTrebleDb by viewModel.eqTrebleDb.collectAsState()
    val bitPerfectUsbEnabled by viewModel.bitPerfectUsbEnabled.collectAsState()
    val context = LocalContext.current
    val pickLyricsFont = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::pickLyricsFont) }
    var selectedIcon by remember { mutableStateOf(IconPicker.current(context)) }
    var pendingIcon by remember { mutableStateOf<LauncherIcon?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            // Wasn't scrollable at all before -- the Tracks-tab NavHost area is a weighted
            // Column child that shrinks when MiniPlayer appears below it, so with a fixed-height
            // Column here the bottom section (Хранилище/Корзина) just got clipped off-screen
            // with no way to reach it while something was playing.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "Настройки",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        SettingsSectionLabel("Иконка приложения")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(if (LauncherIcon.entries.size > 3) 240.dp else 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(LauncherIcon.entries) { icon ->
                    IconChoice(
                        icon = icon,
                        selected = icon == selectedIcon,
                        onClick = { if (icon != selectedIcon) pendingIcon = icon },
                    )
                }
            }
        }

        SettingsSectionLabel("Плеер")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Открывать плеер при выборе трека",
                trailing = { NamiSwitch(checked = autoOpenPlayer, onCheckedChange = viewModel::setAutoOpenPlayer) },
                onClick = { viewModel.setAutoOpenPlayer(!autoOpenPlayer) },
            )
            SettingsRow(
                icon = Icons.Outlined.Fullscreen,
                title = "Скрывать элементы управления телефона",
                trailing = { NamiSwitch(checked = hideSystemBars, onCheckedChange = viewModel::setHideSystemBars) },
                onClick = { viewModel.setHideSystemBars(!hideSystemBars) },
            )
            SettingsRow(
                icon = Icons.Outlined.GraphicEq,
                title = "Караоке-подсветка слов (Beta)",
                trailing = { NamiSwitch(checked = karaokeEnabled, onCheckedChange = viewModel::setKaraokeEnabled) },
                onClick = { viewModel.setKaraokeEnabled(!karaokeEnabled) },
            )
        }

        SettingsSectionLabel("Лирика")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.School,
                title = "Режим изучения (Beta)",
                trailing = { NamiSwitch(checked = studyModeEnabled, onCheckedChange = viewModel::setStudyModeEnabled) },
                onClick = { viewModel.setStudyModeEnabled(!studyModeEnabled) },
            )
            SettingsRow(
                icon = Icons.Outlined.FontDownload,
                title = "Шрифт текста песни",
                trailing = {
                    Text(
                        text = if (lyricsFontPath != null) "Свой" else "Стандартный",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = { pickLyricsFont.launch(arrayOf("font/ttf", "font/otf", "*/*")) },
            )
            if (lyricsFontPath != null) {
                SettingsRow(
                    icon = Icons.Outlined.Close,
                    title = "Сбросить шрифт",
                    trailing = {},
                    onClick = viewModel::clearLyricsFont,
                )
            }
        }

        SettingsSectionLabel("Аудиотракт (Beta)")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.GraphicEq,
                title = "Параметрический EQ",
                trailing = { NamiSwitch(checked = eqEnabled, onCheckedChange = viewModel::setEqEnabled) },
                onClick = { viewModel.setEqEnabled(!eqEnabled) },
            )
            if (eqEnabled) {
                EqSlider("Низкие (100 Гц)", eqBassDb) { viewModel.setEqGains(it, eqMidDb, eqTrebleDb) }
                EqSlider("Средние (1 кГц)", eqMidDb) { viewModel.setEqGains(eqBassDb, it, eqTrebleDb) }
                EqSlider("Высокие (8 кГц)", eqTrebleDb) { viewModel.setEqGains(eqBassDb, eqMidDb, it) }
                Text(
                    text = "Если только что включили -- перезапустите приложение, чтобы EQ реально заработал",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            SettingsRow(
                icon = Icons.Outlined.Usb,
                title = "Bit-perfect по USB (Android 14+)",
                trailing = { NamiSwitch(checked = bitPerfectUsbEnabled, onCheckedChange = viewModel::setBitPerfectUsbEnabled) },
                onClick = { viewModel.setBitPerfectUsbEnabled(!bitPerfectUsbEnabled) },
            )
            if (bitPerfectUsbEnabled) {
                Text(
                    text = "Требует поддержку в HAL производителя -- работает не на всех устройствах, " +
                        "отключает EQ и остальную обработку при активации.",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }

        SettingsSectionLabel("Хранилище")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.Delete,
                title = "Корзина",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onTrashClick,
            )
        }
    }

    pendingIcon?.let { icon ->
        dev.nami.core.designsystem.NamiAlertDialog(
            onDismissRequest = { pendingIcon = null },
            title = { Text("Сменить иконку?", color = NamiColors.Paper100) },
            text = {
                Text(
                    "Значок на рабочем столе может слететь в общий список приложений -- так " +
                        "устроена система, это не баг. Некоторые лаунчеры обновляют иконку только " +
                        "после своего перезапуска. Во время воспроизведения лучше не менять.",
                    color = NamiColors.Paper70,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        IconPicker.select(context, icon)
                        selectedIcon = icon
                        pendingIcon = null
                    },
                ) { Text("Сменить", color = NamiColors.Shu) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingIcon = null }) {
                    Text("Отмена", color = NamiColors.Paper70)
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NamiColors.Ink800, RoundedCornerShape(16.dp))
            .padding(4.dp),
        content = content,
    )
}

@Composable
private fun NamiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = NamiColors.Shu,
            checkedThumbColor = NamiColors.Paper100,
            uncheckedTrackColor = NamiColors.Ink600,
            uncheckedThumbColor = NamiColors.Paper70,
        ),
    )
}

@Composable
private fun EqSlider(label: String, valueDb: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(start = 52.dp, end = 16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text = label, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
            Text(text = "%+.1f дБ".format(valueDb), color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = valueDb,
            onValueChange = onValueChange,
            valueRange = -12f..12f,
            colors = SliderDefaults.colors(
                thumbColor = NamiColors.Shu,
                activeTrackColor = NamiColors.Shu,
                inactiveTrackColor = NamiColors.Ink600,
            ),
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.padding(end = 16.dp))
        Text(text = title, color = NamiColors.Paper100, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun IconChoice(icon: LauncherIcon, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(icon.previewRes),
            contentDescription = icon.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, NamiColors.Shu, RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                    },
                ),
        )
        Text(
            text = icon.label,
            color = if (selected) NamiColors.Shu else NamiColors.Paper70,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
