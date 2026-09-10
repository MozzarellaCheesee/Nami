package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiDisclosure
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiScreenHeader
import dev.nami.core.designsystem.NamiSectionLabel
import dev.nami.core.designsystem.NamiType

/** Top-level Settings screen - just categories, per План.md Часть VIII. Each row opens its own
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
    onBlindListenClick: () -> Unit,
    onCardSortClick: () -> Unit,
    onLocalShareClick: () -> Unit,
    onScrobblingClick: () -> Unit,
    onNetworkSourcesClick: () -> Unit,
    onServerClick: () -> Unit,
    onBatteryClick: () -> Unit,
) {
    var showExtras by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            // Не скроллилось вообще - с ростом числа строк (карточный разбор, DJ-режим,
            // слепое прослушивание и т.д.) нижние пункты просто уезжали за экран, особенно с
            // MiniPlayer, который откусывает ещё часть высоты снизу.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        NamiScreenHeader(title = "Настройки")

        // Раньше все 15 пунктов лежали одним неразличимым списком в одной карточке. Теперь три
        // группы по смыслу (звук и плеер / библиотека / оформление), а редкие режимы-эксперименты
        // и служебное - под "Ещё": их открывают раз в месяц, а место наверху они занимали каждый.
        NamiSectionLabel("Звук и плеер")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            NavRow(Icons.Outlined.PlayCircleOutline, "Плеер", "Макет Now Playing, жесты, перемешивание", onPlayerClick)
            NavRow(Icons.Outlined.GraphicEq, "Аудиотракт", "Эквалайзер, кроссфейд, вывод звука - Beta", onAudioTractClick)
            NavRow(Icons.Outlined.School, "Лирика", "Тексты, перевод, режим изучения", onLyricsClick)
        }

        NamiSectionLabel("Библиотека")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            NavRow(Icons.Outlined.Folder, "Отслеживаемые папки", "Откуда берутся треки", onWatchedFoldersClick)
            NavRow(Icons.Outlined.CheckCircle, "Здоровье библиотеки", "Битые файлы, дубли, пустые теги", onLibraryHealthClick)
            NavRow(Icons.Outlined.BarChart, "Статистика", "Что и сколько слушалось", onStatsClick)
            NavRow(Icons.Outlined.Delete, "Хранилище", "Корзина и занятое место", onTrashClick)
            NavRow(Icons.Outlined.Archive, "Экспорт в .zip", "Треки и плейлисты одним архивом", onExportClick)
        }

        NamiSectionLabel("Оформление")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            NavRow(Icons.Outlined.Palette, "Внешний вид", "Тема, шрифты, иконка, вкладки", onAppearanceClick)
        }

        NamiSectionLabel("Связь")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            NavRow(Icons.Outlined.Share, "Локальная сеть", "Wi-Fi Drop, синхронизация, слушать вместе", onLocalShareClick)
            NavRow(Icons.Outlined.Cast, "Сервер NAMI", "Self-hosted: лирика, анализ, стрим", onServerClick)
            NavRow(Icons.Outlined.BarChart, "Скробблинг", "ListenBrainz", onScrobblingClick)
            NavRow(Icons.Outlined.CloudDownload, "Источники в сети", "Ключи для Jamendo и SoundCloud", onNetworkSourcesClick)
        }

        NamiDisclosure(
            text = "Ещё - режимы и система",
            expanded = showExtras,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            onToggle = { showExtras = !showExtras },
        )
        if (showExtras) {
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                NavRow(Icons.Outlined.GraphicEq, "DJ-режим", "Ручное сведение двух треков", onDjModeClick)
                NavRow(Icons.Outlined.PlayCircleOutline, "Слепое прослушивание", "Угадать трек без обложки и названия", onBlindListenClick)
                NavRow(Icons.Outlined.Shuffle, "Карточный разбор", "Быстрая сортировка библиотеки свайпами", onCardSortClick)
                NavRow(Icons.Outlined.BatteryChargingFull, "Фоновое воспроизведение", "Отключить оптимизацию батареи", onBatteryClick)
            }
        }
    }
}

/** Строка-переход с подписью: без неё пятнадцать одинаковых строк не отличались друг от друга
 * ничем, кроме заголовка, и приходилось заходить внутрь, чтобы вспомнить, что там лежит. */
@Composable
private fun NavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NamiRadius.Button))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.padding(end = 16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = NamiColors.Paper100, style = dev.nami.core.designsystem.NamiType.TrackTitle)
            Text(text = subtitle, color = NamiColors.Paper40, style = dev.nami.core.designsystem.NamiType.Secondary)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40)
    }
}

/** П.md §23.23 "Скробблинг" - ListenBrainz только, свой user-токен (без Last.fm - тот требует
 * зарегистрированное приложение с api_key/api_secret, которых у проекта нет). Отправка идёт из
 * PlayerRepositoryImpl на том же пороге "считается прослушиванием", что и play count. */
@Composable
fun SettingsScrobblingScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.scrobblingEnabled.collectAsState()
    val token by viewModel.listenBrainzToken.collectAsState()
    var tokenText by remember(token) { mutableStateOf(token.orEmpty()) }

    SettingsSubScreenScaffold(title = "Скробблинг", onBack = onBack) {
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.BarChart,
                title = "Отправлять в ListenBrainz",
                trailing = { NamiSwitch(checked = enabled, onCheckedChange = viewModel::setScrobblingEnabled) },
                onClick = { viewModel.setScrobblingEnabled(!enabled) },
            )
        }
        SettingsSectionLabel("ListenBrainz")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Трек отправляется когда прослушано 30 секунд или половина - как и обычный " +
                        "счётчик прослушиваний. Own Last.fm нет - он требует зарегистрированное " +
                        "приложение с отдельными ключами, ListenBrainz работает по своему токену без этого.",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("User token") },
                    singleLine = true,
                    supportingText = { ApiKeyHint("Получить токен: listenbrainz.org/settings", "https://listenbrainz.org/settings/") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                NamiPill(
                    text = "Сохранить токен",
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = { viewModel.setListenBrainzToken(tokenText) },
                )
            }
        }
    }
}

/** Часть VII - self-hosted сервер NAMI. Сопряжение делается сканированием QR из мастера
 * настройки сервера (`nami://auth`, обрабатывается в MainActivity), здесь - только состояние,
 * тумблер «брать лирику с сервера» и отвязка. */
@Composable
fun SettingsServerScreen(
    onBack: () -> Unit,
    onScanClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val url by viewModel.namiServerUrl.collectAsState()
    val token by viewModel.namiServerToken.collectAsState()
    val preferred by viewModel.namiServerPreferred.collectAsState()
    val connectMsg by viewModel.serverConnectMsg.collectAsState()
    val paired = token != null && url.isNotBlank()
    var addr by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    SettingsSubScreenScaffold(title = "Сервер NAMI", onBack = onBack) {
        SettingsSectionLabel(if (paired) "Подключён" else "Не сопряжён")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = if (paired) url else "Отсканируйте QR-код мастера настройки сервера, " +
                        "либо введите адрес и восьмизначный код вручную.",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!paired) {
            SettingsSectionLabel("Сканировать")
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                SettingsRow(
                    icon = Icons.Outlined.Cast,
                    title = "Сканировать QR камерой",
                    subtitle = "Мастер настройки сервера показывает QR",
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                    onClick = onScanClick,
                )
            }

            SettingsSectionLabel("Вручную")
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = addr,
                        onValueChange = { addr = it; viewModel.clearServerConnectMsg() },
                        label = { Text("Адрес сервера") },
                        placeholder = { Text("192.168.1.5:4533") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(8); viewModel.clearServerConnectMsg() },
                        label = { Text("Код сопряжения") },
                        placeholder = { Text("8 цифр из мастера настройки") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    NamiPill(
                        text = "Подключить",
                        modifier = Modifier.padding(top = 12.dp),
                        onClick = { viewModel.connectNamiServer(addr, code) },
                    )
                    if (connectMsg != null) {
                        Text(
                            text = connectMsg!!,
                            color = NamiColors.Paper70,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        if (paired) {
            SettingsSectionLabel("Что берём с сервера")
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                SettingsRow(
                    icon = Icons.Outlined.CloudDownload,
                    title = "Брать лирику с сервера",
                    subtitle = "Поиск, кеш и перевод делает сервер",
                    trailing = { NamiSwitch(checked = preferred, onCheckedChange = viewModel::setNamiServerPreferred) },
                    onClick = { viewModel.setNamiServerPreferred(!preferred) },
                )
            }
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    NamiPill(text = "Отвязать сервер", onClick = viewModel::unpairNamiServer)
                }
            }
        }
    }
}

/** Ключи для вкладки "В сети" (План-Импорт-из-сети-2.md). Оба поля необязательные: пустое просто
 * выключает свой источник в списке чипов, Audius/Archive/Piped ключей не требуют вовсе. Шифровать
 * нечего - это публичные идентификаторы приложения/сайта, а не пароль от личного аккаунта. */
@Composable
fun SettingsNetworkSourcesScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val jamendo by viewModel.jamendoClientId.collectAsState()
    val soundCloud by viewModel.soundCloudClientId.collectAsState()
    var jamendoText by remember(jamendo) { mutableStateOf(jamendo.orEmpty()) }
    var soundCloudText by remember(soundCloud) { mutableStateOf(soundCloud.orEmpty()) }

    SettingsSubScreenScaffold(title = "Источники в сети", onBack = onBack) {
        SettingsSectionLabel("Jamendo")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Каталог музыки под Creative Commons. Ключ выдаётся на приложение, поэтому " +
                        "заведите свой: зарегистрируйтесь на devportal.jamendo.com, создайте приложение " +
                        "и скопируйте сюда его Client ID. Пусто - источник в списке не появится.",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = jamendoText,
                    onValueChange = { jamendoText = it },
                    label = { Text("Jamendo Client ID") },
                    singleLine = true,
                    trailingIcon = {
                        if (jamendoText.isNotEmpty()) {
                            IconButton(onClick = { jamendoText = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Очистить", tint = NamiColors.Paper40)
                            }
                        }
                    },
                    supportingText = { ApiKeyHint("Зарегистрировать приложение: devportal.jamendo.com", "https://devportal.jamendo.com/") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                NamiPill(
                    text = "Сохранить",
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = { viewModel.setJamendoClientId(jamendoText) },
                )
            }
        }

        SettingsSectionLabel("SoundCloud")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Регистрация приложений у SoundCloud закрыта, так что способ неофициальный: " +
                        "берётся тот же ключ, которым работает их собственный сайт. Он время от времени " +
                        "меняется - когда источник перестанет находить треки, достаньте ключ заново.\n\n" +
                        "1. Откройте soundcloud.com в браузере на компьютере.\n" +
                        "2. Нажмите F12 - откроется панель разработчика, перейдите на вкладку Network (Сеть).\n" +
                        "3. Включите на сайте любой трек, чтобы в списке запросов появились новые строки.\n" +
                        "4. Найдите любой запрос к api-v2.soundcloud.com и посмотрите его адрес.\n" +
                        "5. В адресе есть кусок client_id=... - скопируйте то, что после знака равенства.\n" +
                        "6. Вставьте сюда и нажмите Сохранить.",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = soundCloudText,
                    onValueChange = { soundCloudText = it },
                    label = { Text("SoundCloud Client ID") },
                    singleLine = true,
                    trailingIcon = {
                        if (soundCloudText.isNotEmpty()) {
                            IconButton(onClick = { soundCloudText = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Очистить", tint = NamiColors.Paper40)
                            }
                        }
                    },
                    supportingText = { ApiKeyHint("Открыть soundcloud.com", "https://soundcloud.com/") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                NamiPill(
                    text = "Сохранить",
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = { viewModel.setSoundCloudClientId(soundCloudText) },
                )
            }
        }
    }
}

@Composable
internal fun SettingsSubScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            // Same reasoning as the old single-screen version: MiniPlayer can shrink this area,
            // so the bottom of a long category (Аудиотракт especially) needs to stay reachable.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        NamiScreenHeader(title = title, onBack = onBack)
        content()
    }
}

@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    onThemeEditorClick: () -> Unit,
    onBottomTabsClick: () -> Unit,
    onMoreMenuClick: () -> Unit,
    onUiFontClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var selectedIcon by remember { mutableStateOf(IconPicker.current(context)) }
    var pendingIcon by remember { mutableStateOf<LauncherIcon?>(null) }
    // Предложение закрыть приложение показывается ПОСЛЕ смены, а не вместо неё - см. IconPicker.
    var offerRestart by remember { mutableStateOf(false) }
    val amoledEnabled by viewModel.amoledEnabled.collectAsState()
    val uiFontPath by viewModel.uiFontPath.collectAsState()
    SettingsSubScreenScaffold(title = "Внешний вид", onBack = onBack) {
        SettingsSectionLabel("Тема")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.DarkMode,
                title = "AMOLED-чёрный",
                trailing = { NamiSwitch(checked = amoledEnabled, onCheckedChange = viewModel::setAmoledEnabled) },
                onClick = { viewModel.setAmoledEnabled(!amoledEnabled) },
            )
            SettingsRow(
                icon = Icons.Outlined.Palette,
                title = "Редактор темы",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onThemeEditorClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Tune,
                title = "Вкладки нижней панели",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onBottomTabsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.MoreVert,
                title = "Меню \"Ещё\" на плеере",
                subtitle = "Состав, порядок и цвета",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onMoreMenuClick,
            )
            SettingsRow(
                icon = Icons.Outlined.FontDownload,
                title = "Шрифт интерфейса",
                subtitle = "Встроенные наборы или свой файл",
                trailing = {
                    Text(
                        text = uiFontLabel(uiFontPath),
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = onUiFontClick,
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
        Text(
            "Иконку рисует лаунчер из своего кеша. На Samsung, Xiaomi и других фирменных " +
                "оболочках она может обновиться не сразу - если не изменилась, сверни " +
                "приложение, а потом перезапусти лаунчер или телефон.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
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
                        offerRestart = true
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

    // Тихого способа заставить чужой лаунчер перечитать иконку у приложения нет (см. IconPicker.
    // refreshAndKillProcess) - остаётся предложить единственный оставшийся рычаг явно, с честным
    // предупреждением про воспроизведение. Молча процесс не убиваем.
    if (offerRestart) {
        dev.nami.core.designsystem.NamiAlertDialog(
            onDismissRequest = { offerRestart = false },
            title = { Text("Иконка выбрана", color = NamiColors.Paper100) },
            text = {
                Text(
                    "Лаунчер может показать её не сразу. Надёжнее всего закрыть приложение - " +
                        "воспроизведение прервётся, зато иконка обновится с большей вероятностью. " +
                        "Можно и не закрывать: сверни приложение и посмотри на рабочий стол.",
                    color = NamiColors.Paper70,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { IconPicker.refreshAndKillProcess(context) },
                ) { Text("Закрыть приложение", color = NamiColors.Shu) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { offerRestart = false }) {
                    Text("Не закрывать", color = NamiColors.Paper70)
                }
            },
        )
    }
}

@Composable
fun SettingsPlayerScreen(
    onBack: () -> Unit,
    onSessionsClick: () -> Unit,
    onDriveModeClick: () -> Unit,
    onBlockOrderClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val autoOpenPlayer by viewModel.autoOpenPlayer.collectAsState()
    val hideSystemBars by viewModel.hideSystemBars.collectAsState()
    val karaokeEnabled by viewModel.karaokeEnabled.collectAsState()
    val shuffleMode by viewModel.shuffleMode.collectAsState()
    val doubleTapAction by viewModel.doubleTapArtworkAction.collectAsState()
    val nowPlayingShowTechInfo by viewModel.nowPlayingShowTechInfo.collectAsState()
    val nowPlayingShowShuffle by viewModel.nowPlayingShowShuffle.collectAsState()
    val nowPlayingShowRepeat by viewModel.nowPlayingShowRepeat.collectAsState()
    val nowPlayingCompactCover by viewModel.nowPlayingCompactCover.collectAsState()
    val nowPlayingLineProgress by viewModel.nowPlayingLineProgress.collectAsState()
    val miniPlayerSideSwipe by viewModel.miniPlayerSideSwipeAction.collectAsState()
    val layoutPreset by viewModel.nowPlayingLayoutPreset.collectAsState()
    val airPlayEnabled by viewModel.airPlayEnabled.collectAsState()
    val yandexEnabled by viewModel.yandexStationEnabled.collectAsState()
    val yandexToken by viewModel.yandexOAuthToken.collectAsState()
    val yandexClientId by viewModel.yandexClientId.collectAsState()
    var yandexTokenText by remember(yandexToken) { mutableStateOf(yandexToken.orEmpty()) }
    var yandexClientIdText by remember(yandexClientId) { mutableStateOf(yandexClientId.orEmpty()) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    SettingsSubScreenScaffold(title = "Плеер", onBack = onBack) {
        NowPlayingPresetRow(
            selected = layoutPreset,
            onSelect = viewModel::applyNowPlayingPreset,
        )
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
            SettingsRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Дорожный режим",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onDriveModeClick,
            )
            SettingsRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Двойной тап по обложке",
                trailing = {
                    Text(
                        text = when (doubleTapAction) {
                            dev.nami.domain.GestureAction.NONE -> "Ничего"
                            dev.nami.domain.GestureAction.TOGGLE_LIKE -> "Любимый трек"
                            dev.nami.domain.GestureAction.SKIP_NEXT -> "Следующий трек"
                            dev.nami.domain.GestureAction.PLAY_PAUSE -> "Пауза/играть"
                            dev.nami.domain.GestureAction.SHOW_LYRICS -> "Показать лирику"
                        },
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {
                    val values = dev.nami.domain.GestureAction.entries
                    val next = values[(values.indexOf(doubleTapAction) + 1) % values.size]
                    viewModel.setDoubleTapArtworkAction(next)
                },
            )
            // §13 "действия свайпов настраиваются". Значений ровно два, а не весь GestureAction:
            // свайп по мини-плееру двусторонний (влево/вправо), и единственное действие, у
            // которого есть осмысленные обе стороны - листание трека. "Лайк влево и лайк вправо"
            // действием не является, поэтому остальные варианты сюда не пускаем.
            SettingsRow(
                icon = Icons.Outlined.Tune,
                title = "Свайп вбок по мини-плееру",
                trailing = {
                    Text(
                        text = when (miniPlayerSideSwipe) {
                            dev.nami.domain.GestureAction.SKIP_NEXT -> "Листать треки"
                            else -> "Ничего"
                        },
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {
                    val next = if (miniPlayerSideSwipe == dev.nami.domain.GestureAction.SKIP_NEXT) {
                        dev.nami.domain.GestureAction.NONE
                    } else {
                        dev.nami.domain.GestureAction.SKIP_NEXT
                    }
                    viewModel.setMiniPlayerSideSwipeAction(next)
                },
            )
            SettingsRow(
                icon = Icons.Outlined.GraphicEq,
                title = "Показывать техинфо трека (формат/битрейт)",
                trailing = { NamiSwitch(checked = nowPlayingShowTechInfo, onCheckedChange = viewModel::setNowPlayingShowTechInfo) },
                onClick = { viewModel.setNowPlayingShowTechInfo(!nowPlayingShowTechInfo) },
            )
            SettingsRow(
                icon = Icons.Outlined.Shuffle,
                title = "Кнопка перемешать на Now Playing",
                trailing = { NamiSwitch(checked = nowPlayingShowShuffle, onCheckedChange = viewModel::setNowPlayingShowShuffle) },
                onClick = { viewModel.setNowPlayingShowShuffle(!nowPlayingShowShuffle) },
            )
            SettingsRow(
                icon = Icons.Outlined.Repeat,
                title = "Кнопка зациклить на Now Playing",
                trailing = { NamiSwitch(checked = nowPlayingShowRepeat, onCheckedChange = viewModel::setNowPlayingShowRepeat) },
                onClick = { viewModel.setNowPlayingShowRepeat(!nowPlayingShowRepeat) },
            )
            SettingsRow(
                icon = Icons.Outlined.Album,
                title = "Компактная обложка на Now Playing",
                trailing = { NamiSwitch(checked = nowPlayingCompactCover, onCheckedChange = viewModel::setNowPlayingCompactCover) },
                onClick = { viewModel.setNowPlayingCompactCover(!nowPlayingCompactCover) },
            )
            SettingsRow(
                icon = Icons.Outlined.GraphicEq,
                title = "Прогресс линией вместо волны (без меток моментов)",
                trailing = { NamiSwitch(checked = nowPlayingLineProgress, onCheckedChange = viewModel::setNowPlayingLineProgress) },
                onClick = { viewModel.setNowPlayingLineProgress(!nowPlayingLineProgress) },
            )
            SettingsRow(
                icon = Icons.Outlined.Tune,
                title = "Порядок блоков плеера",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onBlockOrderClick,
            )
        }
        // Трансляция отдельной карточкой: Google Cast и DLNA работают всегда и настройки не
        // требуют, а эти два пути неофициальные - явный opt-in, а не тумблер среди прочих.
        Text(
            text = "Трансляция",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.Cast,
                title = "Передача на устройства Apple (Beta)",
                subtitle = "Apple TV по AirPlay. Пока выключено, сеть на AirPlay не сканируется. " +
                    "HomePod и AirPlay-колонки не поддерживаются: им нужен протокол RAOP.",
                trailing = { NamiSwitch(checked = airPlayEnabled, onCheckedChange = viewModel::setAirPlayEnabled) },
                onClick = { viewModel.setAirPlayEnabled(!airPlayEnabled) },
            )
            SettingsRow(
                icon = Icons.Outlined.Cast,
                title = "Трансляция на Яндекс Станцию (Beta)",
                subtitle = "Протокол Glagol неофициальный: Яндекс вправе сломать его без " +
                    "предупреждения. Нужен вход в личный Яндекс ID. Пока выключено, сеть не сканируется.",
                trailing = { NamiSwitch(checked = yandexEnabled, onCheckedChange = viewModel::setYandexStationEnabled) },
                onClick = { viewModel.setYandexStationEnabled(!yandexEnabled) },
            )
        }
        // Вход появляется только при включённом тумблере: без трансляции на Станцию токен
        // Яндекс ID приложению не нужен и просить его незачем.
        if (yandexEnabled) {
            SettingsCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "Вход идёт в вашем браузере, приложение не видит пароль. Заведите своё " +
                            "приложение на oauth.yandex.ru (нужны права \"Яндекс.Станция\"), впишите его " +
                            "ID ниже и нажмите Войти - браузер покажет токен, его и вставьте.",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = yandexClientIdText,
                        onValueChange = { yandexClientIdText = it },
                        label = { Text("ID приложения Яндекс ID") },
                        singleLine = true,
                        supportingText = { ApiKeyHint("Создать приложение: oauth.yandex.ru", "https://oauth.yandex.ru/") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    NamiPill(
                        text = "Войти в Яндекс ID",
                        enabled = yandexClientIdText.isNotBlank(),
                        modifier = Modifier.padding(top = 12.dp),
                        onClick = {
                            viewModel.setYandexClientId(yandexClientIdText)
                            uriHandler.openUri(
                                "https://oauth.yandex.ru/authorize?response_type=token" +
                                    "&client_id=${yandexClientIdText.trim()}" +
                                    "&redirect_uri=https://oauth.yandex.ru/verification_code",
                            )
                        },
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = yandexTokenText,
                        onValueChange = { yandexTokenText = it },
                        label = { Text("OAuth-токен") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    NamiPill(
                        text = "Сохранить токен",
                        modifier = Modifier.padding(top = 12.dp),
                        onClick = { viewModel.setYandexOAuthToken(yandexTokenText) },
                    )
                }
            }
        }
    }
}

/** П.md §17 "5 готовых пресетов макета". Карточка не отдельный режим экрана, а кнопка "записать
 * сразу весь набор переключателей ниже" - поэтому она стоит НАД списком: пресет выбирают первым,
 * дальше правят по одному. CUSTOM своей карточки не имеет: это не выбор, а состояние "трогали
 * переключатели руками", и нажать на него было бы нечем. */
@Composable
private fun NowPlayingPresetRow(
    selected: dev.nami.domain.NowPlayingLayoutPreset,
    onSelect: (dev.nami.domain.NowPlayingLayoutPreset) -> Unit,
) {
    val presets = listOf(
        dev.nami.domain.NowPlayingLayoutPreset.CLASSIC to "Классический",
        dev.nami.domain.NowPlayingLayoutPreset.BIG_COVER to "Крупная обложка",
        dev.nami.domain.NowPlayingLayoutPreset.COMPACT to "Компактный",
        dev.nami.domain.NowPlayingLayoutPreset.LYRICS_FIRST to "Лирика-первая",
        dev.nami.domain.NowPlayingLayoutPreset.MINIMAL to "Минимал",
    )
    Text(
        text = "Макет плеера",
        color = NamiColors.Paper70,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        presets.forEach { (preset, label) ->
            val isActive = preset == selected
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .clip(RoundedCornerShape(NamiRadius.Card))
                    .background(NamiColors.Ink800)
                    .border(
                        width = if (isActive) 2.dp else 1.dp,
                        color = if (isActive) NamiColors.Shu else NamiColors.Ink500,
                        shape = RoundedCornerShape(NamiRadius.Card),
                    )
                    .clickable { onSelect(preset) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetThumbnail(preset)
                Text(
                    text = label,
                    color = if (isActive) NamiColors.Shu else NamiColors.Paper70,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Схематичная «превьюшка» пресета: прямоугольник обложки нужного размера плюс полоски на месте
 * названия/прогресса/транспорта. Рисуется из значений самого пресета, а не задаётся картинкой -
 * иначе превью разъедется с раскладкой при первой же правке layoutOf(). */
@Composable
private fun PresetThumbnail(preset: dev.nami.domain.NowPlayingLayoutPreset) {
    val layout = dev.nami.domain.layoutOf(preset)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(NamiRadius.Chip))
            .background(NamiColors.Ink900)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (layout.compactCover) 0.42f else 0.72f)
                .height(if (layout.compactCover) 14.dp else 24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NamiColors.Paper40),
        )
        layout.blockOrder.filter { it != dev.nami.domain.NowPlayingBlock.TECH_INFO || layout.showTechInfo }
            .take(3)
            .forEach { block ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (block == dev.nami.domain.NowPlayingBlock.PROGRESS) 0.86f else 0.56f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NamiColors.Ink500),
                )
            }
    }
}

@Composable
fun SettingsLyricsScreen(
    onBack: () -> Unit,
    onLyricsFontClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val studyModeEnabled by viewModel.studyModeEnabled.collectAsState()
    val lyricsFontPath by viewModel.lyricsFontPath.collectAsState()
    val stands4Uid by viewModel.stands4Uid.collectAsState()
    val stands4Token by viewModel.stands4Token.collectAsState()
    val stands4RequestsToday by viewModel.stands4RequestsToday.collectAsState()
    val deeplApiKey by viewModel.deeplApiKey.collectAsState()
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
                subtitle = "Встроенные наборы или свой файл",
                trailing = {
                    Text(
                        text = uiFontLabel(lyricsFontPath),
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = onLyricsFontClick,
            )
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

/** Clickable "where to get this key" line - goes in an OutlinedTextField's supportingText, right
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
private fun SettingsSectionLabel(text: String) = NamiSectionLabel(text)

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .padding(4.dp),
        content = content,
    )
}

@Composable
internal fun NamiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
internal fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NamiRadius.Button))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.padding(end = 16.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = NamiColors.Paper100, style = NamiType.TrackTitle)
            if (subtitle != null) {
                Text(text = subtitle, color = NamiColors.Paper40, style = NamiType.Secondary)
            }
        }
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

/** План.md §22.11 "Сессии" - save the current EQ/crossfade state (plus an optional sleep timer)
 * under a name, re-apply any saved one in one tap. Deliberately doesn't snapshot the queue itself
 * - see SessionsViewModel's own doc for why. */
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
        NamiPill(
            text = "Сохранить текущие настройки как сессию",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            onClick = { showSaveDialog = true },
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
