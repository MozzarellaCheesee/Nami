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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shuffle
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

/** Top-level Settings screen -- just categories, per План.md Часть VIII. Each row opens its own
 * screen instead of everything living in one long scroll (that's what this replaced: one Column
 * with every setting from every category inlined, which grew unreadable as categories were added). */
@Composable
fun SettingsScreen(
    onTrashClick: () -> Unit,
    onAudioTractClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onPlayerClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onLibraryHealthClick: () -> Unit,
    onStatsClick: () -> Unit,
    onWatchedFoldersClick: () -> Unit,
    onExportClick: () -> Unit,
    onDjModeClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900).padding(bottom = 24.dp)) {
        Text(
            text = "Настройки",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Плеер",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onPlayerClick,
            )
            SettingsRow(
                icon = Icons.Outlined.School,
                title = "Лирика",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onLyricsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.GraphicEq,
                title = "Аудиотракт (Beta)",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onAudioTractClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Palette,
                title = "Внешний вид",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onAppearanceClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Delete,
                title = "Хранилище",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onTrashClick,
            )
            SettingsRow(
                icon = Icons.Outlined.CheckCircle,
                title = "Здоровье библиотеки",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onLibraryHealthClick,
            )
            SettingsRow(
                icon = Icons.Outlined.BarChart,
                title = "Статистика",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onStatsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Folder,
                title = "Отслеживаемые папки",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onWatchedFoldersClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Archive,
                title = "Экспорт библиотеки в .zip",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onExportClick,
            )
            SettingsRow(
                icon = Icons.Outlined.GraphicEq,
                title = "DJ-режим",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onDjModeClick,
            )
        }
    }
}

@Composable
private fun SettingsSubScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            // Same reasoning as the old single-screen version: MiniPlayer can shrink this area,
            // so the bottom of a long category (Аудиотракт especially) needs to stay reachable.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                text = title,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        content()
    }
}

@Composable
fun SettingsAppearanceScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var selectedIcon by remember { mutableStateOf(IconPicker.current(context)) }
    var pendingIcon by remember { mutableStateOf<LauncherIcon?>(null) }
    val amoledEnabled by viewModel.amoledEnabled.collectAsState()

    SettingsSubScreenScaffold(title = "Внешний вид", onBack = onBack) {
        SettingsSectionLabel("Тема")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.DarkMode,
                title = "AMOLED-чёрный",
                trailing = { NamiSwitch(checked = amoledEnabled, onCheckedChange = viewModel::setAmoledEnabled) },
                onClick = { viewModel.setAmoledEnabled(!amoledEnabled) },
            )
        }
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
    }

    pendingIcon?.let { icon ->
        dev.nami.core.designsystem.NamiAlertDialog(
            onDismissRequest = { pendingIcon = null },
            title = { Text("Сменить иконку?", color = NamiColors.Paper100) },
            text = {
                Text(
                    "Значок на рабочем столе может слететь в общий список приложений - так " +
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
fun SettingsPlayerScreen(onBack: () -> Unit, onSessionsClick: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val autoOpenPlayer by viewModel.autoOpenPlayer.collectAsState()
    val hideSystemBars by viewModel.hideSystemBars.collectAsState()
    val karaokeEnabled by viewModel.karaokeEnabled.collectAsState()
    val shuffleMode by viewModel.shuffleMode.collectAsState()

    SettingsSubScreenScaffold(title = "Плеер", onBack = onBack) {
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
            SettingsRow(
                icon = Icons.Outlined.Shuffle,
                title = "Перемешивание",
                trailing = {
                    Text(
                        text = when (shuffleMode) {
                            dev.nami.domain.ShuffleMode.TRUE_RANDOM -> "Случайно"
                            dev.nami.domain.ShuffleMode.WEIGHTED_BY_STALENESS -> "Давно не играло"
                        },
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {
                    val next = when (shuffleMode) {
                        dev.nami.domain.ShuffleMode.TRUE_RANDOM -> dev.nami.domain.ShuffleMode.WEIGHTED_BY_STALENESS
                        dev.nami.domain.ShuffleMode.WEIGHTED_BY_STALENESS -> dev.nami.domain.ShuffleMode.TRUE_RANDOM
                    }
                    viewModel.setShuffleMode(next)
                },
            )
            SettingsRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Сессии (Учёба/Дорога/Сон)",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onSessionsClick,
            )
        }
    }
}

@Composable
fun SettingsLyricsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val studyModeEnabled by viewModel.studyModeEnabled.collectAsState()
    val lyricsFontPath by viewModel.lyricsFontPath.collectAsState()
    val stands4Uid by viewModel.stands4Uid.collectAsState()
    val stands4Token by viewModel.stands4Token.collectAsState()
    val stands4RequestsToday by viewModel.stands4RequestsToday.collectAsState()
    val deeplApiKey by viewModel.deeplApiKey.collectAsState()
    val pickLyricsFont = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::pickLyricsFont) }

    SettingsSubScreenScaffold(title = "Лирика", onBack = onBack) {
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

        SettingsSectionLabel("STANDS4 (резервный источник текстов)")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Используется только если LRCLIB не нашёл текст. Бесплатный лимит: 100 запросов в день.",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = stands4Uid,
                    onValueChange = viewModel::setStands4Uid,
                    label = { Text("UID") },
                    singleLine = true,
                    supportingText = { ApiKeyHint("Получить UID и Token: stands4.com/api.php", "https://www.stands4.com/api.php") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = stands4Token,
                    onValueChange = viewModel::setStands4Token,
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    text = "Запросов сегодня: $stands4RequestsToday / ${dev.nami.domain.SettingsRepository.STANDS4_DAILY_LIMIT}",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        SettingsSectionLabel("DeepL (перевод текста песни)")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Заметно лучше переводит с японского, чем встроенный офлайн-переводчик. " +
                        "Бесплатный лимит: 500 000 символов в месяц. Пусто: перевод остаётся " +
                        "офлайн (хуже качеством, но без ключа и сети).",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = deeplApiKey,
                    onValueChange = viewModel::setDeeplApiKey,
                    label = { Text("API-ключ") },
                    singleLine = true,
                    supportingText = { ApiKeyHint("Получить ключ: deepl.com/pro-api", "https://www.deepl.com/pro-api") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

/** Clickable "where to get this key" line -- goes in an OutlinedTextField's supportingText, right
 * under the field it belongs to, instead of one combined paragraph above a whole group of fields
 * (STANDS4's UID+Token used to share one, which didn't say which field the link was even for). */
@Composable
private fun ApiKeyHint(text: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        text = text,
        color = NamiColors.Ai,
        style = MaterialTheme.typography.bodySmall,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        modifier = Modifier.clickable { uriHandler.openUri(url) },
    )
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

/** План.md §22.11 "Сессии" -- save the current EQ/crossfade state (plus an optional sleep timer)
 * under a name, re-apply any saved one in one tap. Deliberately doesn't snapshot the queue itself
 * -- see SessionsViewModel's own doc for why. */
@Composable
fun SessionsScreen(onBack: () -> Unit, viewModel: SessionsViewModel = hiltViewModel()) {
    val sessions by viewModel.sessions.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }

    SettingsSubScreenScaffold(title = "Сессии", onBack = onBack) {
        Text(
            text = "Сохраняет текущий EQ и кроссфейд под именем, опционально с таймером сна. " +
                "Очередь и громкость плеера сессия не запоминает.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            if (sessions.isEmpty()) {
                Text(
                    text = "Сессий пока нет",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            sessions.forEach { session ->
                SettingsRow(
                    icon = Icons.Outlined.PlayCircleOutline,
                    title = session.name,
                    trailing = {
                        androidx.compose.material3.IconButton(onClick = { viewModel.deleteSession(session.name) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Удалить", tint = NamiColors.Paper40)
                        }
                    },
                    onClick = { viewModel.applySession(session) },
                )
            }
        }
        Text(
            text = "+ Сохранить текущие настройки как сессию",
            color = NamiColors.Shu,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .clickable { showSaveDialog = true },
        )
    }

    if (showSaveDialog) {
        SaveSessionDialog(
            onSave = { name, minutes ->
                viewModel.saveCurrentAsSession(name, minutes)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}

@Composable
private fun SaveSessionDialog(onSave: (name: String, sleepTimerMinutes: Int?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var withTimer by remember { mutableStateOf(false) }
    var minutesText by remember { mutableStateOf("30") }

    dev.nami.core.designsystem.NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая сессия") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Например: Учёба") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp).clickable { withTimer = !withTimer },
                ) {
                    androidx.compose.material3.Checkbox(checked = withTimer, onCheckedChange = { withTimer = it })
                    Text("Запускать таймер сна", color = NamiColors.Paper70)
                }
                if (withTimer) {
                    androidx.compose.material3.OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { c -> c.isDigit() } },
                        label = { Text("Минут") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onSave(name.trim(), if (withTimer) minutesText.toIntOrNull() else null)
            }) { Text("Сохранить") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
