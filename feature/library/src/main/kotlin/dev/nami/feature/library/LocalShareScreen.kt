package dev.nami.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.domain.DiscoveredDevice
import dev.nami.domain.WifiDirectPeer

/** Группа G "сеть" - один экран, три сценария (Wi-Fi Drop, синхронизация, слушать вместе) на
 * общем списке найденных по NSD устройств + ручной ввод/QR как запасной путь.
 *
 * Раскладка сверху вниз по частоте использования, а не по порядку реализации: своё устройство и
 * QR -> что происходит прямо сейчас -> два сценария-переключателя -> найденные устройства ->
 * свёрнутое "ещё" (Wi-Fi Direct и ручной ввод адреса). */
@Composable
fun LocalShareScreen(
    onBack: () -> Unit,
    onScanRequested: () -> Unit,
    scannedAddress: String? = null,
    onScannedAddressConsumed: () -> Unit = {},
    // Вход с двух кнопок в меню "Ещё" Now Playing ("Поделиться треком"/"Слушать со мной") -
    // экран открывается сразу в нужном режиме, а не заставляет искать те же переключатели
    // руками второй раз после захода с плеера.
    autoShareCurrentTrack: Boolean = false,
    autoStartListenTogether: Boolean = false,
    onAutoActionsConsumed: () -> Unit = {},
    viewModel: LocalShareViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (autoShareCurrentTrack) viewModel.setDropCurrentTrack()
        if (autoStartListenTogether) viewModel.setListenTogetherHost(true)
        if (autoShareCurrentTrack || autoStartListenTogether) onAutoActionsConsumed()
    }
    val serverRunning by viewModel.serverRunning.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val dropTrack by viewModel.dropTrack.collectAsState()
    val hostEnabled by viewModel.listenTogetherHostEnabled.collectAsState()
    val guestCount by viewModel.listenTogetherGuestCount.collectAsState()
    val guestState by viewModel.listenTogetherGuestState.collectAsState()
    val listenTogetherError by viewModel.listenTogetherError.collectAsState()
    val lastSyncResult by viewModel.lastSyncResult.collectAsState()
    val lastPullResult by viewModel.lastPullResult.collectAsState()
    val dropError by viewModel.dropError.collectAsState()
    val wifiDirectPeers by viewModel.wifiDirectPeers.collectAsState()
    val wifiDirectConnecting by viewModel.wifiDirectConnecting.collectAsState()
    val wifiDirectConnected by viewModel.wifiDirectConnected.collectAsState()

    // То же разрешение нужно не только Wi-Fi Direct, но и обычному NSD-автопоиску (Wi-Fi Drop/
    // синхронизация/слушать вместе): на Android 13+ NsdManager без NEARBY_WIFI_DEVICES тихо не
    // находит вообще ничего (не падает, не предупреждает - просто пустой discoveredDevices). До
    // 13 то же самое было ACCESS_FINE_LOCATION. Раньше разрешение просилось только по кнопке
    // Wi-Fi Direct, поэтому обычный сценарий "слушать вместе" молча не работал без неё.
    val context = LocalContext.current
    val wifiDirectPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    var hasWifiDirectPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, wifiDirectPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasWifiDirectPermission = granted
        if (granted) {
            viewModel.startWifiDirectDiscovery()
            viewModel.restartDiscovery()
        }
    }

    var showQr by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(scannedAddress) {
        scannedAddress?.let {
            manualText = it
            // Поле ручного ввода живёт в свёрнутом "ещё" - после скана его надо показать, иначе
            // результат сканирования уезжает в невидимую секцию.
            showMore = true
            onScannedAddressConsumed()
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (hasWifiDirectPermission) {
            viewModel.startWifiDirectDiscovery()
        } else {
            // Спрашиваем сразу при открытии экрана, а не только по кнопке Wi-Fi Direct - без
            // этого NSD-автопоиск (Wi-Fi Drop/синхронизация/слушать вместе) остаётся слепым на
            // Android 13+, см. комментарий у объявления разрешения выше.
            wifiDirectPermissionLauncher.launch(wifiDirectPermission)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Локальная сеть", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Своё устройство - адрес, статус сервера и QR для тех, кого не видит автопоиск.
            item {
                NetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (serverRunning) NamiColors.Wakaba else NamiColors.Paper40)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                when {
                                    !serverRunning -> "Сервер не запущен"
                                    serverAddress == null -> "Не подключён к Wi-Fi"
                                    else -> "Видно другим устройствам"
                                },
                                color = NamiColors.Paper100,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                serverAddress ?: if (serverRunning) "Включи Wi-Fi или Wi-Fi Direct, чтобы тебя было видно" else "адрес появится после запуска",
                                color = NamiColors.Paper40,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { showQr = !showQr }) {
                            Icon(
                                Icons.Outlined.QrCode,
                                contentDescription = "Показать QR",
                                tint = if (showQr) NamiColors.Shu else NamiColors.Paper70,
                            )
                        }
                    }
                    AnimatedVisibility(visible = showQr && serverAddress != null) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                                QrCodeImage(content = serverAddress.orEmpty(), modifier = Modifier.size(200.dp))
                            }
                            Text(
                                "Отсканируйте с устройства, которого нет в автопоиске",
                                color = NamiColors.Paper40,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            // 2. Разрешение - без него слеп ВЕСЬ экран, поэтому предупреждение вверху, а не в
            // секции Wi-Fi Direct, откуда оно раньше просилось.
            if (!hasWifiDirectPermission) {
                item {
                    NetCard(accent = NamiColors.Kin) {
                        Text("Нужен доступ к устройствам рядом", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Без него автопоиск не находит вообще никого",
                            color = NamiColors.Paper40,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(modifier = Modifier.padding(top = 12.dp)) {
                            Pill("Разрешить", NamiColors.Kin) { wifiDirectPermissionLauncher.launch(wifiDirectPermission) }
                        }
                    }
                }
            }

            // 3. Что происходит прямо сейчас - единственная карточка с цветной рамкой, поэтому
            // взгляд цепляется за неё первой. Нет активности - карточки нет вообще.
            val hasActivity = guestState != null || dropTrack != null || listenTogetherError != null
            if (hasActivity) {
                item {
                    NetCard(accent = if (listenTogetherError != null) NamiColors.Shu else NamiColors.Wakaba) {
                        SectionTitle(Icons.Outlined.Headphones, "Сейчас", NamiColors.Wakaba)
                        listenTogetherError?.let { error ->
                            // Самый частый случай - хост не включил "показывать что играю" или
                            // недоступен; без этой строки кнопка "Слушать вместе" выглядела как
                            // кнопка, которая ничего не делает. Выход тут же, иначе опрос некому
                            // остановить.
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                Text(error, color = NamiColors.Shu, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Pill("Отменить", NamiColors.Paper70) { viewModel.leaveListenTogether() }
                            }
                        }
                        guestState?.let { g ->
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text(
                                    "Слушаю вместе с ${g.hostName}" + if (g.otherGuests > 0) " и ещё ${g.otherGuests}" else "",
                                    color = NamiColors.Paper40,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(g.trackTitle ?: "-", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
                                g.artistName?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall) }
                                if (g.downloading) {
                                    Text("Скачивается...", color = NamiColors.Ai, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                                    if (!g.downloading && g.cachedPath != null) {
                                        Pill("В библиотеку", NamiColors.Shu) {
                                            viewModel.addCurrentListenTogetherTrackToLibrary { ok ->
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (ok) "Трек добавлен в библиотеку" else "Не удалось сохранить трек",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                    Pill("Выйти", NamiColors.Paper70) { viewModel.leaveListenTogether() }
                                }
                            }
                        }
                        dropTrack?.let { track ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Раздаётся", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
                                    Text(track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                                }
                                Pill("Стоп", NamiColors.Paper70) { viewModel.clearDropTrack() }
                            }
                        }
                    }
                }
            }

            // 4. Два сценария-переключателя: раздать трек и пустить к себе слушать вместе.
            item { SectionLabel("Что отдаю") }
            item {
                NetCard {
                    SectionTitle(Icons.Outlined.Send, "Wi-Fi Drop", NamiColors.Shu)
                    Text(
                        "Один трек по прямой ссылке - другое устройство заберёт его кнопкой \"Получить\"",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Pill(if (dropTrack == null) "Раздать играющий трек" else "Сменить на играющий", NamiColors.Shu) {
                            viewModel.setDropCurrentTrack()
                        }
                    }
                    dropError?.let {
                        Text(it, color = NamiColors.Shu, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    lastPullResult?.let {
                        Text(
                            if (it) "Трек получен и добавлен в библиотеку" else "Не получилось скачать",
                            color = if (it) NamiColors.Wakaba else NamiColors.Shu,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item {
                NetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            SectionTitle(Icons.Outlined.Groups, "Слушать вместе", NamiColors.Wakaba)
                            // Тумблер сам по себе слепой: хост не видел, подключился ли вообще
                            // кто-то. Число гостей ограничения не имеет - слушать могут сколько
                            // угодно устройств в сети, каждое само по себе.
                            Text(
                                when {
                                    !hostEnabled -> "Показывать что играю - другие смогут подключиться"
                                    guestCount == 0 -> "Пока никто не слушает"
                                    else -> "Сейчас слушают: $guestCount"
                                },
                                color = if (hostEnabled && guestCount > 0) NamiColors.Wakaba else NamiColors.Paper40,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Switch(
                            checked = hostEnabled,
                            onCheckedChange = { viewModel.setListenTogetherHost(it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = NamiColors.Wakaba,
                                checkedThumbColor = NamiColors.Paper100,
                                uncheckedTrackColor = NamiColors.Ink600,
                                uncheckedThumbColor = NamiColors.Paper70,
                            ),
                        )
                    }
                }
            }

            // 5. Найденные устройства - одна карточка со строками, а не россыпь по полотну.
            item { SectionLabel(if (devices.isEmpty()) "Устройства рядом" else "Устройства рядом - ${devices.size}") }
            item {
                NetCard {
                    if (devices.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Devices, contentDescription = null, tint = NamiColors.Paper40, modifier = Modifier.size(20.dp))
                            Text(
                                "Пока никого - другое устройство должно открыть этот же экран",
                                color = NamiColors.Paper40,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    devices.forEachIndexed { index, device ->
                        if (index > 0) Divider()
                        DeviceRow(
                            device = device,
                            onPullDrop = { viewModel.pullDrop(device) },
                            onSync = { viewModel.syncWith(device) },
                            onJoinListenTogether = { viewModel.joinListenTogether(device) },
                        )
                    }
                    if (manualText.isNotBlank()) {
                        val manualDevice = viewModel.addManualDevice(manualText)
                        // Тот же хост уже виден автопоиском (NSD) - показывать его ещё раз
                        // отдельной строкой "ip:port" вместо человекочитаемого "NAMI-..." только
                        // путает.
                        if (manualDevice != null && devices.none { it.host == manualDevice.host }) {
                            if (devices.isNotEmpty()) Divider()
                            DeviceRow(
                                device = manualDevice,
                                onPullDrop = { viewModel.pullDrop(manualDevice) },
                                onSync = { viewModel.syncWith(manualDevice) },
                                onJoinListenTogether = { viewModel.joinListenTogether(manualDevice) },
                            )
                        }
                    }
                    lastSyncResult?.let { count ->
                        Text(
                            "Синхронизировано треков: $count",
                            color = NamiColors.Wakaba,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            // 6. Редкие способы под "ещё": Wi-Fi Direct (нужен только без роутера) и ручной
            // ввод адреса/скан QR (запасной путь, когда автопоиск не видит).
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NamiRadius.Button))
                        .clickable { showMore = !showMore }
                        .padding(vertical = 8.dp),
                ) {
                    Text("Другие способы", color = NamiColors.Paper70, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(
                        if (showMore) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = NamiColors.Paper40,
                    )
                }
            }
            if (showMore) {
                item {
                    NetCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                SectionTitle(Icons.Outlined.WifiTethering, "Wi-Fi Direct", NamiColors.Ai)
                                Text(
                                    "Без роутера и интернета",
                                    color = NamiColors.Paper40,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (wifiDirectConnected) StatusDot(NamiColors.Wakaba)
                        }
                        if (hasWifiDirectPermission) {
                            // "Искать рядом" доступно ВСЕГДА, в том числе при активной группе:
                            // раньше при подключении кнопка пропадала целиком, и подключиться ко
                            // второму устройству (или просто обновить список) было нечем.
                            Row(modifier = Modifier.padding(top = 12.dp)) {
                                Pill(if (wifiDirectConnecting) "Подключение..." else "Искать рядом", NamiColors.Ai) {
                                    viewModel.startWifiDirectDiscovery()
                                }
                            }
                            if (wifiDirectPeers.isEmpty()) {
                                Text("Никого не видно рядом", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                            }
                            wifiDirectPeers.forEach { peer ->
                                PeerRow(
                                    peer = peer,
                                    connecting = wifiDirectConnecting,
                                    onConnect = { viewModel.connectWifiDirect(peer) },
                                    onDisconnect = { viewModel.disconnectWifiDirect() },
                                )
                            }
                        } else {
                            Row(modifier = Modifier.padding(top = 12.dp)) {
                                Pill("Разрешить поиск устройств рядом", NamiColors.Ai) {
                                    wifiDirectPermissionLauncher.launch(wifiDirectPermission)
                                }
                            }
                        }
                    }
                }
                item {
                    NetCard {
                        SectionTitle(Icons.Outlined.QrCodeScanner, "Адрес вручную", NamiColors.Paper70)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = manualText,
                                onValueChange = { manualText = it },
                                label = { Text("IP:порт") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onScanRequested) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Сканировать QR", tint = NamiColors.Paper100)
                            }
                        }
                        Text(
                            "Введённый адрес появится в списке устройств выше",
                            color = NamiColors.Paper40,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** Базовая карточка экрана - как SettingsCard в настройках, плюс необязательная цветная рамка
 * для карточек, требующих внимания (активная сессия, отсутствующее разрешение). */
@Composable
private fun NetCard(accent: Color? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .then(
                if (accent != null) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(NamiRadius.Card))
                } else {
                    Modifier
                },
            )
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Text(text, color = NamiColors.Paper100, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NamiColors.Ink600))
}

/** Пилюля вместо TextButton - у карточек и без того много текста одного размера, кнопке нужна
 * своя форма, чтобы её было видно как кнопку. */
@Composable
private fun Pill(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) color else NamiColors.Paper40
    Text(
        text = text,
        color = tint,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(NamiRadius.Button))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(NamiRadius.Button))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    onPullDrop: () -> Unit,
    onSync: () -> Unit,
    onJoinListenTogether: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Devices, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(device.name, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
                Text("${device.host}:${device.port}", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Обычный Row без ширины отдаёт остаток места ПОСЛЕДНЕМУ ребёнку - на узких экранах
        // "Слушать вместе" (самый длинный текст) получал меньше всего места и переносился на
        // вторую строку, из-за чего сама кнопка (и её ripple) растягивалась по высоте.
        // horizontalScroll не даёт ни одной кнопке сжаться - переносится сама строка, не текст.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        ) {
            Pill("Получить раздачу", NamiColors.Shu, onClick = onPullDrop)
            Pill("Синхр.", NamiColors.Ai, onClick = onSync)
            Pill("Слушать вместе", NamiColors.Wakaba, onClick = onJoinListenTogether)
        }
    }
}

@Composable
private fun PeerRow(peer: WifiDirectPeer, connecting: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer.name,
                color = if (peer.connected) NamiColors.Wakaba else NamiColors.Paper100,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                peer.status,
                color = if (peer.connected) NamiColors.Wakaba else NamiColors.Paper40,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Сопряжение видно на самой строке устройства, а не только общей плашкой сверху: так
        // понятно, С КЕМ именно связь (и это одинаково работает с обеих сторон - и у владельца
        // группы, и у клиента, см. refreshWifiDirectGroup).
        if (peer.connected) {
            Pill("Отключить", NamiColors.Shu, onClick = onDisconnect)
        } else {
            Pill("Подключить", NamiColors.Ai, enabled = !connecting, onClick = onConnect)
        }
    }
}

/** Стилизованный QR - данные-модули кружками (не квадратами), три угловых finder-паттерна
 * остаются сплошными скруглёнными квадратами (их форма и позиция - то, по чему сканер вообще
 * находит QR в кадре, трогать нельзя). Уровень коррекции ошибок H (30%) специально взят с
 * запасом - кружки вместо квадратов уже съедают часть точности, дальше урезать нечем. */
@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H)
        val code = com.google.zxing.qrcode.encoder.Encoder.encode(content, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, hints)
        val matrix = code.matrix
        val moduleCount = matrix.width
        val quietZone = 2
        val totalModules = moduleCount + quietZone * 2
        val pixelSize = 480
        val moduleSize = pixelSize / totalModules

        val bmp = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val background = AndroidColor.parseColor("#EDEAE4") // NamiColors.Paper100
        val foreground = AndroidColor.parseColor("#0C0D0F") // NamiColors.Ink900
        canvas.drawColor(background)

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = foreground }
        fun isFinderZone(x: Int, y: Int): Boolean {
            val inTopLeft = x < 7 && y < 7
            val inTopRight = x >= moduleCount - 7 && y < 7
            val inBottomLeft = x < 7 && y >= moduleCount - 7
            return inTopLeft || inTopRight || inBottomLeft
        }
        for (x in 0 until moduleCount) {
            for (y in 0 until moduleCount) {
                if (matrix.get(x, y).toInt() != 1) continue
                val left = (x + quietZone) * moduleSize.toFloat()
                val top = (y + quietZone) * moduleSize.toFloat()
                if (isFinderZone(x, y)) {
                    canvas.drawRect(left, top, left + moduleSize, top + moduleSize, paint)
                } else {
                    val cx = left + moduleSize / 2f
                    val cy = top + moduleSize / 2f
                    canvas.drawCircle(cx, cy, moduleSize * 0.42f, paint)
                }
            }
        }
        bmp
    }
    androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR-код адреса", modifier = modifier)
}
