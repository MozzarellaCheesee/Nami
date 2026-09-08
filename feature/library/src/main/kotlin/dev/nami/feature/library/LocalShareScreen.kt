package dev.nami.feature.library

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.DiscoveredDevice

/** Группа G "сеть" - один экран, три сценария (Wi-Fi Drop, синхронизация, слушать вместе) на
 * общем списке найденных по NSD устройств + ручной ввод/QR как запасной путь. */
@Composable
fun LocalShareScreen(
    onBack: () -> Unit,
    onScanRequested: () -> Unit,
    scannedAddress: String? = null,
    onScannedAddressConsumed: () -> Unit = {},
    viewModel: LocalShareViewModel = hiltViewModel(),
) {
    val serverRunning by viewModel.serverRunning.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val dropTrack by viewModel.dropTrack.collectAsState()
    val hostEnabled by viewModel.listenTogetherHostEnabled.collectAsState()
    val guestState by viewModel.listenTogetherGuestState.collectAsState()
    val lastSyncResult by viewModel.lastSyncResult.collectAsState()
    val lastPullResult by viewModel.lastPullResult.collectAsState()

    var showQr by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(scannedAddress) {
        scannedAddress?.let {
            manualText = it
            onScannedAddressConsumed()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Локальная сеть", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    if (serverRunning) "Видно другим устройствам как ${serverAddress.orEmpty()}" else "Сервер не запущен",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    IconButton(onClick = { showQr = !showQr }) {
                        Icon(Icons.Outlined.QrCode, contentDescription = "Показать QR", tint = NamiColors.Paper100)
                    }
                    Text("Показать QR (для устройств, которых нет в автопоиске)", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showQr && serverAddress != null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
                        QrCodeImage(content = serverAddress!!, modifier = Modifier.size(220.dp))
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        label = { Text("IP:порт вручную") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onScanRequested) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Сканировать QR", tint = NamiColors.Paper100)
                    }
                }
            }

            item {
                Text("Раздача трека (Wi-Fi Drop)", color = NamiColors.Paper40, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                Text(
                    dropTrack?.let { "Раздаю: ${it.title}" } ?: "Ничего не раздаётся",
                    color = if (dropTrack != null) NamiColors.Shu else NamiColors.Paper70,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    TextButton(onClick = { viewModel.setDropCurrentTrack() }) { Text("Раздать играющий трек", color = NamiColors.Shu) }
                    if (dropTrack != null) {
                        TextButton(onClick = { viewModel.clearDropTrack() }) { Text("Стоп", color = NamiColors.Paper70) }
                    }
                }
                lastPullResult?.let {
                    Text(if (it) "Трек получен и добавлен в библиотеку" else "Не получилось скачать", color = if (it) NamiColors.Wakaba else NamiColors.Shu, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                    Text("Слушать вместе - показывать что играю", color = NamiColors.Paper70, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = hostEnabled,
                        onCheckedChange = { viewModel.setListenTogetherHost(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = NamiColors.Wakaba),
                    )
                }
            }

            if (guestState != null) {
                item {
                    val g = guestState!!
                    Column(modifier = Modifier.fillMaxWidth().background(NamiColors.Ink800, RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Text("Слушаю вместе с ${g.hostName}", color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                        Text(g.trackTitle ?: "-", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
                        g.artistName?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall) }
                        if (g.downloading) {
                            Text("Скачивается...", color = NamiColors.Ai, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            if (!g.downloading && g.cachedPath != null) {
                                TextButton(onClick = { viewModel.addCurrentListenTogetherTrackToLibrary() }) { Text("Добавить в библиотеку", color = NamiColors.Shu) }
                            }
                            TextButton(onClick = { viewModel.leaveListenTogether() }) { Text("Выйти", color = NamiColors.Paper70) }
                        }
                    }
                }
            }

            item {
                Text("Найденные устройства", color = NamiColors.Paper40, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            if (devices.isEmpty()) {
                item { Text("Пока никого - другое устройство должно открыть этот же экран", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall) }
            }
            items(devices, key = { it.host + it.port }) { device ->
                DeviceRow(
                    device = device,
                    onPullDrop = { viewModel.pullDrop(device) },
                    onSync = { viewModel.syncWith(device) },
                    onJoinListenTogether = { viewModel.joinListenTogether(device) },
                )
            }
            if (manualText.isNotBlank()) {
                item {
                    val manualDevice = viewModel.addManualDevice(manualText)
                    if (manualDevice != null) {
                        DeviceRow(
                            device = manualDevice,
                            onPullDrop = { viewModel.pullDrop(manualDevice) },
                            onSync = { viewModel.syncWith(manualDevice) },
                            onJoinListenTogether = { viewModel.joinListenTogether(manualDevice) },
                        )
                    }
                }
            }
            lastSyncResult?.let { count ->
                item { Text("Синхронизировано треков: $count", color = NamiColors.Wakaba, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
            item { Box(modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    onPullDrop: () -> Unit,
    onSync: () -> Unit,
    onJoinListenTogether: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(device.name, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
        Text("${device.host}:${device.port}", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
        // Обычный Row без ширины отдаёт остаток места ПОСЛЕДНЕМУ ребёнку - на узких экранах
        // "Слушать вместе" (самый длинный текст) получал меньше всего места и переносился на
        // вторую строку, из-за чего сама кнопка (и её ripple) растягивалась по высоте.
        // horizontalScroll не даёт ни одной кнопке сжаться - переносится сама строка, не текст.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            TextButton(onClick = onPullDrop) { Text("Получить раздачу", color = NamiColors.Shu) }
            TextButton(onClick = onSync) { Text("Синхр.", color = NamiColors.Ai) }
            TextButton(onClick = onJoinListenTogether) { Text("Слушать вместе", color = NamiColors.Paper100) }
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
