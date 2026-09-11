package dev.nami.feature.player

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QrCode
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import dev.nami.core.designsystem.ImmersiveSheetEffect
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
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

/**
 * Выезжающая снизу панель (ModalBottomSheet) для совместного прослушивания (Джем) через сервер.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(
    onDismiss: () -> Unit,
    viewModel: JamViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val error by viewModel.error.collectAsState()
    val isServerConfigured by viewModel.isServerConfigured.collectAsState()
    val activeHostUrl by viewModel.activeHostUrl.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NamiColors.Ink900,
    ) {
        ImmersiveSheetEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Джем",
                        style = MaterialTheme.typography.titleLarge,
                        color = NamiColors.Paper100,
                    )
                    Text(
                        text = "Совместное прослушивание через сервер",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper40,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Закрыть",
                        tint = NamiColors.Paper70,
                    )
                }
            }

            // Error banner
            error?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NamiColors.Shu.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(NamiRadius.Card),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = NamiColors.Paper100,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Закрыть ошибку",
                                tint = NamiColors.Paper70,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            val currentSession = session
            if (currentSession == null) {
                JamSheetNoSession(
                    isServerConfigured = isServerConfigured,
                    activeHostUrl = activeHostUrl,
                    onCreateRoom = { viewModel.createRoom() },
                    onJoinRoom = { code, host -> viewModel.joinRoom(code, host) },
                )
            } else {
                JamSheetActiveSession(
                    session = currentSession,
                    connected = connected,
                    activeHostUrl = activeHostUrl,
                    onLeave = { viewModel.leave() },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun JamSheetNoSession(
    isServerConfigured: Boolean,
    activeHostUrl: String? = null,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String, String?) -> Unit,
) {
    var joinCode by remember { mutableStateOf("") }
    var hostUrl by remember { mutableStateOf(activeHostUrl ?: "") }
    var showAdvancedHost by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Create Room Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Создать комнату",
                    style = MaterialTheme.typography.titleMedium,
                    color = NamiColors.Paper100,
                )
                Text(
                    text = if (isServerConfigured) {
                        "Начните совместное прослушивание. Другие участники смогут подключиться по коду комнаты или QR-коду без настройки сервера."
                    } else {
                        "Для создания комнаты необходимо подключить сервер NAMI в Настройках (вы выступаете хостом сессии)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isServerConfigured) NamiColors.Paper70 else NamiColors.Paper40,
                )
                Button(
                    onClick = onCreateRoom,
                    enabled = isServerConfigured,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
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

        // Join Room Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Присоединиться к комнате",
                    style = MaterialTheme.typography.titleMedium,
                    color = NamiColors.Paper100,
                )
                Text(
                    text = "Введите 6-значный код комнаты, ссылку-приглашение или адрес сервера хоста. Личный сервер не требуется.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NamiColors.Paper70,
                )
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { input ->
                        joinCode = if (input.startsWith("nami://") || input.contains("http://") || input.contains("https://")) {
                            input.trim()
                        } else {
                            input.uppercase().filter { it in ALLOWED_JAM_CODE_CHARS }.take(6)
                        }
                    },
                    enabled = true,
                    label = { Text("Код комнаты или ссылка") },
                    placeholder = { Text("Например ABC234") },
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

                if (showAdvancedHost || hostUrl.isNotBlank()) {
                    OutlinedTextField(
                        value = hostUrl,
                        onValueChange = { hostUrl = it.trim() },
                        enabled = true,
                        label = { Text("Адрес сервера хоста (если не в одном Wi-Fi)") },
                        placeholder = { Text("http://192.168.1.100:4533") },
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
                } else {
                    TextButton(
                        onClick = { showAdvancedHost = true },
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text(
                            text = "+ Указать адрес сервера вручную",
                            style = MaterialTheme.typography.bodySmall,
                            color = NamiColors.Paper40,
                        )
                    }
                }

                val canJoin = joinCode.isNotBlank() && (joinCode.length >= 6 || joinCode.contains("jam"))
                Button(
                    onClick = {
                        val trimmedCode = joinCode.trim()
                        val customHost = hostUrl.trim().ifBlank { null }
                        onJoinRoom(trimmedCode, customHost)
                    },
                    enabled = canJoin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
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
private fun JamSheetActiveSession(
    session: JamSession,
    connected: Boolean,
    activeHostUrl: String? = null,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showQrDialog by remember { mutableStateOf(false) }

    val hostPart = activeHostUrl?.let { "&host=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
    val inviteLink = "nami://jam?code=${session.code}$hostPart"

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = {
                Text(
                    text = "QR-код для подключения",
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
                        text = "Отсканируйте камерой или сканером в Nami",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper70,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Room Code & Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Код комнаты (нажмите, чтобы скопировать)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NamiColors.Paper40,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(NamiRadius.Button))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(session.code))
                            Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = session.code,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = NamiColors.Paper100,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Скопировать код",
                        tint = NamiColors.Shu,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
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
                        text = if (session.isHost) "Организатор" else "Участник (Гость)",
                        style = MaterialTheme.typography.bodySmall,
                        color = NamiColors.Paper70,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Присоединяйся к совместному прослушиванию в Nami!\nКод комнаты: ${session.code}\nСсылка: $inviteLink",
                                )
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Пригласить в Jam"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(NamiRadius.Button),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = NamiColors.Paper100,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Поделиться", color = NamiColors.Paper100)
                    }

                    OutlinedButton(
                        onClick = { showQrDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(NamiRadius.Button),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = null,
                            tint = NamiColors.Paper100,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QR-код", color = NamiColors.Paper100)
                    }
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
                Text(
                    text = "Текущий трек",
                    style = MaterialTheme.typography.labelMedium,
                    color = NamiColors.Paper40,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.Chip)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = NamiColors.Shu,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column {
                        Text(
                            text = session.currentTrackId?.let { "Трек #$it" } ?: "Нет текущего трека",
                            style = MaterialTheme.typography.titleMedium,
                            color = NamiColors.Paper100,
                        )
                        if (session.currentTrackId != null && session.positionMs > 0) {
                            val seconds = (session.positionMs / 1000) % 60
                            val minutes = (session.positionMs / 1000) / 60
                            Text(
                                text = String.format("%02d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.bodySmall,
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
            Column(modifier = Modifier.padding(16.dp)) {
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
                Spacer(modifier = Modifier.height(8.dp))
                if (session.queue.isEmpty()) {
                    Text(
                        text = "Очередь пуста",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NamiColors.Paper40,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    session.queue.forEachIndexed { index, trackId ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = NamiColors.Ink700,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NamiColors.Paper40,
                                modifier = Modifier.width(28.dp),
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
                .height(48.dp),
            shape = RoundedCornerShape(NamiRadius.Button),
            colors = ButtonDefaults.buttonColors(
                containerColor = NamiColors.Shu,
                contentColor = NamiColors.Paper100,
            ),
        ) {
            Text(
                text = "Покинуть сессию",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
