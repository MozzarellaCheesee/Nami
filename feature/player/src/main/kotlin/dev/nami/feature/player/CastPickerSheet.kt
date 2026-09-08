package dev.nami.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiCard
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiSectionLabel
import dev.nami.player.remote.RemoteKind

/**
 * Единственный вход в трансляцию: одна кнопка "Трансляция" - один лист, в нём группы по
 * экосистемам. Google Cast остаётся за штатным диалогом androidx.mediarouter (свой список
 * устройств у него внутри Cast SDK, дублировать его нечем), остальные приёмники - свой список.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CastPickerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val viewModel: CastPickerViewModel = hiltViewModel()
    val devices by viewModel.devices.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val error by viewModel.error.collectAsState()

    // Поиск запускается при открытии листа, а не в фоне приложения: сканировать сеть, когда никто
    // не выбирает устройство, незачем.
    LaunchedEffect(Unit) { viewModel.search() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Трансляция", style = MaterialTheme.typography.titleLarge)
            Text(
                "Во время трансляции эквалайзер, ReplayGain, кроссфейд и остальной звуковой тракт " +
                    "не действуют: файл декодирует сам приёмник.",
                style = MaterialTheme.typography.bodySmall,
            )

            error?.let { message ->
                NamiCard { Text(message, style = MaterialTheme.typography.bodyMedium) }
            }

            connected?.let { device ->
                NamiSectionLabel("Сейчас транслируется")
                DeviceRow(device.name, iconFor(device.kind), subtitle = "Нажмите, чтобы отключиться") {
                    viewModel.disconnect()
                }
            }

            NamiSectionLabel("Google Cast")
            DeviceRow("Chromecast, Google TV, Android TV", Icons.Outlined.Cast, subtitle = "Открыть список устройств") {
                openCastPicker(context)
            }

            RemoteKind.entries.forEach { kind ->
                val group = devices.filter { it.kind == kind }
                val enabled = viewModel.isKindEnabled(kind)
                if (!enabled) return@forEach
                NamiSectionLabel(titleFor(kind))
                if (group.isEmpty()) {
                    Text(
                        if (searching) "Поиск..." else "Ничего не найдено",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    group.forEach { device ->
                        DeviceRow(device.name, iconFor(kind), subtitle = if (device.isBeta) "Beta" else device.host) {
                            viewModel.connect(device)
                            onDismiss()
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NamiPill(text = if (searching) "Идёт поиск" else "Искать снова", selected = false) { viewModel.search() }
                if (searching) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(title: String, icon: ImageVector, subtitle: String?, onClick: () -> Unit) {
    NamiCard(modifier = Modifier.clickable(onClick = onClick), padding = PaddingValues(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun titleFor(kind: RemoteKind): String = when (kind) {
    RemoteKind.DLNA -> "DLNA"
    RemoteKind.AIRPLAY -> "Устройства Apple (Beta)"
    RemoteKind.YANDEX -> "Яндекс Станция (Beta)"
}

private fun iconFor(kind: RemoteKind): ImageVector = when (kind) {
    RemoteKind.DLNA -> Icons.Outlined.Tv
    RemoteKind.AIRPLAY -> Icons.Outlined.Tv
    RemoteKind.YANDEX -> Icons.Outlined.Speaker
}
