package dev.nami.feature.player

import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamScreen(
    onBack: () -> Unit,
    viewModel: JamViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val error by viewModel.error.collectAsState()
    val isServerConfigured by viewModel.isServerConfigured.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    isServerConfigured = isServerConfigured,
                    onCreateRoom = { viewModel.createRoom() },
                    onJoinRoom = { code -> viewModel.joinRoom(code) },
                )
            } else {
                JamSessionContent(
                    session = currentSession,
                    connected = connected,
                    onLeave = { viewModel.leave() },
                )
            }
        }
    }
}

@Composable
private fun JamNoSessionContent(
    isServerConfigured: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var joinCode by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                        "Начните совместное прослушивание. Другие участники смогут подключиться по коду комнаты."
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
                    text = "Введите 6-значный код комнаты, чтобы слушать музыку вместе.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NamiColors.Paper70,
                )
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { input ->
                        val filtered = input.uppercase().filter { it in ALLOWED_JAM_CODE_CHARS }.take(6)
                        joinCode = filtered
                    },
                    label = { Text("Код комнаты") },
                    placeholder = { Text("6 символов") },
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
                Button(
                    onClick = { onJoinRoom(joinCode) },
                    enabled = joinCode.length == 6,
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
    session: JamSession,
    connected: Boolean,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
            }
        }

        // Current Track Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NamiColors.Ink800),
            shape = RoundedCornerShape(NamiRadius.Card),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Текущий трек",
                    style = MaterialTheme.typography.labelMedium,
                    color = NamiColors.Paper40,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.Chip)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = NamiColors.Shu,
                            modifier = Modifier.size(24.dp),
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
