package dev.nami.feature.player

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import dev.nami.domain.PlaybackState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiSnackbarHost
import dev.nami.domain.JamSession

private const val ALLOWED_JAM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    if (content.isBlank()) return
    val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H)
    val code = runCatching {
        com.google.zxing.qrcode.encoder.Encoder.encode(content, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, hints)
    }.getOrNull() ?: return
    val matrix = code.matrix
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cellW = w / matrix.width
        val cellH = h / matrix.height
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() == 1) {
                    drawRect(Color.Black, Offset(x * cellW, y * cellH), Size(cellW, cellH))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamScreen(
    onBack: () -> Unit,
    onScanRequested: (() -> Unit)? = null,
    scannedQr: String? = null,
    onScannedQrConsumed: (() -> Unit)? = null,
    viewModel: JamViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val error by viewModel.error.collectAsState()
    val isServerConfigured by viewModel.isServerConfigured.collectAsState()
    val activeHostUrl by viewModel.activeHostUrl.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(scannedQr) {
        val qr = scannedQr ?: return@LaunchedEffect
        viewModel.joinRoom(qr)
        onScannedQrConsumed?.invoke()
    }

    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Закрыть",
        )
        viewModel.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Джем",
                        style = MaterialTheme.typography.titleLarge,
                        color = NamiColors.Paper100,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = NamiColors.Paper100,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NamiColors.Ink900,
                ),
            )
        },
        snackbarHost = {
            NamiSnackbarHost(snackbarHostState)
        },
        containerColor = NamiColors.Ink900,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val currentSession = session
            if (currentSession == null) {
                JamNoSessionContent(
                    viewModel = viewModel,
                    isServerConfigured = isServerConfigured,
                    onCreateRoom = { viewModel.createRoom() },
                    onJoinRoom = { code, host -> viewModel.joinRoom(code, host) },
                    onScanRequested = onScanRequested,
                )
            } else {
                JamSessionContent(
                    viewModel = viewModel,
                    session = currentSession,
                    connected = connected,
                    activeHostUrl = activeHostUrl,
                    onLeave = { viewModel.leave() },
                )
            }
        }
    }
}

@Composable
private fun JamNoSessionContent(
    viewModel: JamViewModel,
    isServerConfigured: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String, String?) -> Unit,
    onScanRequested: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var joinCode by remember { mutableStateOf("") }
    var hostAddress by remember { mutableStateOf("") }

    val clipboardManager = LocalClipboardManager.current
    val clipboardText = remember { clipboardManager.getText()?.text }
    val detectedInvite = remember(clipboardText) { viewModel.parseJamInvite(clipboardText) }
    val recentHosts by viewModel.recentHosts.collectAsState()
    val discoveredRooms by viewModel.discoveredRooms.collectAsState()
    val context = LocalContext.current
    var showCameraScanner by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val text = viewModel.decodeQrFromUri(context, uri)
            if (text != null && viewModel.joinFromQrText(text)) {
                Toast.makeText(context, "Подключение к комнате...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "QR-код не найден на изображении", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showCameraScanner) {
        JamCameraScannerDialog(
            onDismiss = { showCameraScanner = false },
            onQrScanned = { qrText ->
                if (viewModel.joinFromQrText(qrText)) {
                    Toast.makeText(context, "Подключение к комнате...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Некорректный QR-код Джема", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Автоматически обнаруженные комнаты рядом (Wi-Fi или сервер)
        if (discoveredRooms.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
                shape = RoundedCornerShape(NamiRadius.Card),
                border = BorderStroke(1.dp, NamiColors.Shu.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Groups,
                                contentDescription = null,
                                tint = NamiColors.Shu,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Доступные комнаты рядом",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NamiColors.Paper100,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(NamiColors.Shu.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${discoveredRooms.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NamiColors.Shu,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    discoveredRooms.forEach { room ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NamiColors.Ink700)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "Комната ${room.code}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = NamiColors.Paper100,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (room.source == dev.nami.domain.JamDiscoverySource.LOCAL_WIFI) NamiColors.Wakaba.copy(alpha = 0.2f) else NamiColors.Shu.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (room.source == dev.nami.domain.JamDiscoverySource.LOCAL_WIFI) "Wi-Fi" else "Сервер",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (room.source == dev.nami.domain.JamDiscoverySource.LOCAL_WIFI) NamiColors.Wakaba else NamiColors.Shu,
                                        )
                                    }
                                }
                                room.description?.let { desc ->
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NamiColors.Paper40,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Button(
                                onClick = { onJoinRoom(room.code, room.hostUrl) },
                                shape = RoundedCornerShape(NamiRadius.Button),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NamiColors.Shu,
                                    contentColor = NamiColors.Paper100,
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            ) {
                                Text("Войти", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Карточка быстрого входа из буфера обмена
        if (detectedInvite != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
                shape = RoundedCornerShape(NamiRadius.Card),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ссылка из буфера обмена",
                            style = MaterialTheme.typography.labelSmall,
                            color = NamiColors.Wakaba,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Комната ${detectedInvite.first}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = NamiColors.Paper100,
                        )
                        detectedInvite.second?.let { host ->
                            Text(
                                text = host,
                                style = MaterialTheme.typography.bodySmall,
                                color = NamiColors.Paper40,
                                maxLines = 1,
                            )
                        }
                    }
                    Button(
                        onClick = { onJoinRoom(detectedInvite.first, detectedInvite.second) },
                        shape = RoundedCornerShape(NamiRadius.Button),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NamiColors.Wakaba,
                            contentColor = NamiColors.Ink900,
                        ),
                    ) {
                        Text("Войти", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Создать комнату",
                    style = MaterialTheme.typography.titleMedium,
                    color = NamiColors.Paper100,
                )
                Text(
                    text = if (isServerConfigured) {
                        "Начните совместное прослушивание. Другие участники смогут подключиться по коду комнаты, ссылке или QR-коду."
                    } else {
                        "Для создания комнаты необходимо подключить сервер NAMI в Настройках."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isServerConfigured) NamiColors.Paper70 else NamiColors.Paper40,
                )
                Button(
                    onClick = onCreateRoom,
                    enabled = isServerConfigured,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(NamiRadius.Button),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NamiColors.Shu,
                        contentColor = NamiColors.Paper100,
                        disabledContainerColor = NamiColors.Ink600,
                        disabledContentColor = NamiColors.Paper40,
                    ),
                ) {
                    Text(
                        text = "Создать комнату",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Присоединиться к комнате",
                    style = MaterialTheme.typography.titleMedium,
                    color = NamiColors.Paper100,
                )
                Text(
                    text = "Введите 6-значный код комнаты, ссылку-приглашение или отсканируйте QR-код организатора. Личный сервер не требуется.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NamiColors.Paper70,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { input ->
                            joinCode = input
                        },
                        label = { Text("Код или ссылка") },
                        placeholder = { Text("ABC234 или nami://jam?...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(NamiRadius.Card),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = NamiColors.Paper100,
                            unfocusedTextColor = NamiColors.Paper100,
                            focusedBorderColor = NamiColors.Shu,
                            unfocusedBorderColor = NamiColors.Ink600,
                            focusedLabelColor = NamiColors.Shu,
                            unfocusedLabelColor = NamiColors.Paper40,
                            cursorColor = NamiColors.Shu,
                        ),
                    )
                    IconButton(
                        onClick = {
                            if (onScanRequested != null) onScanRequested.invoke() else showCameraScanner = true
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.Card)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = "Сканировать QR камерой",
                            tint = NamiColors.Paper100,
                        )
                    }
                    IconButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier
                            .size(50.dp)
                            .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.Card)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = "Выбрать QR из галереи",
                            tint = NamiColors.Paper100,
                        )
                    }
                }

                // Кнопки быстрого сканирования
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (onScanRequested != null) onScanRequested.invoke() else showCameraScanner = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(NamiRadius.Button),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NamiColors.Paper100),
                        border = BorderStroke(1.dp, NamiColors.Shu.copy(alpha = 0.5f)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = NamiColors.Shu,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Камерой", style = MaterialTheme.typography.bodyMedium)
                    }

                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(NamiRadius.Button),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NamiColors.Paper100),
                        border = BorderStroke(1.dp, NamiColors.Ink600),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = NamiColors.Paper70,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Из галереи", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Недавние серверы хостов
                if (recentHosts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Недавние серверы хостов:",
                            style = MaterialTheme.typography.labelSmall,
                            color = NamiColors.Paper40,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recentHosts.forEach { rh ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (hostAddress == rh) NamiColors.Shu.copy(alpha = 0.25f) else NamiColors.Ink700)
                                        .clickable {
                                            hostAddress = rh
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = rh,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hostAddress == rh) NamiColors.Paper100 else NamiColors.Paper70,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isServerConfigured) {
                    OutlinedTextField(
                        value = hostAddress,
                        onValueChange = { hostAddress = it },
                        label = { Text("Адрес сервера хоста (если не в одном Wi-Fi)") },
                        placeholder = { Text("https://my-nami.ts.net или http://192.168.1.50:4533") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(NamiRadius.Card),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = NamiColors.Paper100,
                            unfocusedTextColor = NamiColors.Paper100,
                            focusedBorderColor = NamiColors.Shu,
                            unfocusedBorderColor = NamiColors.Ink600,
                            focusedLabelColor = NamiColors.Shu,
                            unfocusedLabelColor = NamiColors.Paper40,
                            cursorColor = NamiColors.Shu,
                        ),
                    )
                    Text(
                        text = "В общей Wi-Fi сети комната определится автоматически без ввода адреса.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper40,
                    )
                }

                Button(
                    onClick = { onJoinRoom(joinCode, hostAddress.ifBlank { null }) },
                    enabled = joinCode.trim().isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(NamiRadius.Button),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NamiColors.Shu,
                        contentColor = NamiColors.Paper100,
                        disabledContainerColor = NamiColors.Ink600,
                        disabledContentColor = NamiColors.Paper40,
                    ),
                ) {
                    Text(
                        text = "Присоединиться",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun JamSessionContent(
    viewModel: JamViewModel,
    session: JamSession,
    connected: Boolean,
    activeHostUrl: String?,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showQrDialog by remember { mutableStateOf(false) }
    val uploadStatus by viewModel.uploadStatus.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    val allHosts by viewModel.allHostUrls.collectAsState()
    val externalHost = allHosts.firstOrNull { !viewModel.isLocalHost(it) }
    val preferredHost = externalHost ?: activeHostUrl
    val hostsList = (listOfNotNull(externalHost) + allHosts + listOfNotNull(activeHostUrl)).distinct()
    val hostsParam = if (hostsList.isNotEmpty()) "&hosts=${java.net.URLEncoder.encode(hostsList.joinToString(","), "UTF-8")}" else ""
    val hostParam = preferredHost?.let { "&host=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
    val inviteLink = "nami://jam?code=${session.code}$hostParam$hostsParam"
    val webInviteLink = preferredHost?.let { "$it/jam?code=${session.code}" }

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = {
                Text(
                    text = "QR-код для подключения к Jam",
                    style = MaterialTheme.typography.titleMedium,
                    color = NamiColors.Paper100,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Участники могут отсканировать этот код в Nami и слушать музыку вместе с вами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper70,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(NamiRadius.Card))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrCodeImage(
                            content = inviteLink,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = session.code,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = NamiColors.Paper100,
                        letterSpacing = 4.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Закрыть", color = NamiColors.Shu)
                }
            },
            containerColor = NamiColors.Ink800,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Подсказка, если сервер доступен только в локальной сети
        if (session.isHost && viewModel.isLocalHost(preferredHost)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
                shape = RoundedCornerShape(NamiRadius.Card),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = NamiColors.Shu,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Подключение вне домашнего Wi-Fi",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NamiColors.Paper100,
                        )
                        Text(
                            text = "Сейчас сервер подключён по локальному адресу. Чтобы друзья могли подключаться через 4G или удалённо, настройте внешний адрес (Tailscale / Cloudflare Tunnel / домен) в Настройки → Сервер NAMI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NamiColors.Paper70,
                        )
                    }
                }
            }
        }

        // Room Code & Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Код комнаты",
                    style = MaterialTheme.typography.labelMedium,
                    color = NamiColors.Paper40,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(NamiRadius.Button))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(session.code))
                            Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = session.code,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = NamiColors.Paper100,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Скопировать код",
                        tint = NamiColors.Shu,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (connected) NamiColors.Wakaba else NamiColors.Shu,
                                shape = CircleShape,
                            ),
                    )
                    Text(
                        text = if (connected) "Подключено" else "Отключено",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) NamiColors.Wakaba else NamiColors.Shu,
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper40,
                    )
                    Text(
                        text = if (session.isHost) "Организатор" else "Участник",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper70,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val shareText = buildString {
                                appendLine("Присоединяйся к совместному прослушиванию в Nami Jam!")
                                appendLine("Код комнаты: ${session.code}")
                                if (webInviteLink != null) {
                                    appendLine("Ссылка: $webInviteLink")
                                }
                                appendLine("В приложении Nami: $inviteLink")
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Пригласить в Jam"))
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(NamiRadius.Button),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = NamiColors.Paper100,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Поделиться",
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(webInviteLink ?: inviteLink))
                            Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(NamiRadius.Button),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = null,
                            tint = NamiColors.Paper100,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Ссылка",
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    OutlinedButton(
                        onClick = { showQrDialog = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(NamiRadius.Button),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = null,
                            tint = NamiColors.Paper100,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "QR-код",
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        // Upload Status Card (On-the-fly streaming sync)
        uploadStatus?.let { statusText ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NamiColors.Shu.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(NamiRadius.Card),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = NamiColors.Shu,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NamiColors.Paper100,
                    )
                }
            }
        }

        // Current Track Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Текущий трек",
                        style = MaterialTheme.typography.labelMedium,
                        color = NamiColors.Paper40,
                        modifier = Modifier.weight(1f),
                    )
                    if (nowPlaying != null) {
                        val isPlaying = (playbackState as? PlaybackState.Playing)?.isPlaying == true
                        Text(
                            text = if (isPlaying) "Воспроизведение" else "Пауза",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlaying) NamiColors.Wakaba else NamiColors.Paper40,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val currentTrack = nowPlaying
                    if (currentTrack?.artworkPath != null) {
                        AsyncImage(
                            model = currentTrack.artworkPath,
                            contentDescription = currentTrack.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(NamiColors.Ink700),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(NamiColors.Ink700, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MusicNote,
                                contentDescription = null,
                                tint = NamiColors.Shu,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nowPlaying?.title
                                ?: session.currentTrackId?.let { "Трек #$it" }
                                ?: "Нет текущего трека",
                            style = MaterialTheme.typography.titleMedium,
                            color = NamiColors.Paper100,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = nowPlaying?.artistName
                                ?: if (session.isHost) "Включите трек в плеере для трансляции" else "Ожидание воспроизведения от организатора",
                            style = MaterialTheme.typography.bodySmall,
                            color = NamiColors.Paper70,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val posMs = (playbackState as? PlaybackState.Playing)?.positionMs ?: session.positionMs
                        val durMs = (playbackState as? PlaybackState.Playing)?.durationMs ?: 0L
                        if (durMs > 0L) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val curSec = (posMs / 1000) % 60
                            val curMin = (posMs / 1000) / 60
                            val totalSec = (durMs / 1000) % 60
                            val totalMin = (durMs / 1000) / 60
                            Text(
                                text = String.format("%02d:%02d / %02d:%02d", curMin, curSec, totalMin, totalSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = NamiColors.Paper40,
                            )
                        }
                    }
                }
            }
        }

        // Queue Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Очередь",
                        style = MaterialTheme.typography.titleMedium,
                        color = NamiColors.Paper100,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${session.queue.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NamiColors.Paper40,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (session.queue.isEmpty()) {
                    Text(
                        text = "Очередь пуста",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NamiColors.Paper40,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    session.queue.forEachIndexed { index, trackId ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = NamiColors.Ink700,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NamiColors.Paper40,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = "Трек #$trackId",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NamiColors.Paper100,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // Leave Button
        Button(
            onClick = onLeave,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(NamiRadius.Button),
            colors = ButtonDefaults.buttonColors(
                containerColor = NamiColors.Shu,
                contentColor = NamiColors.Paper100,
            ),
        ) {
            Text(
                text = "Покинуть",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
