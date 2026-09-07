package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.Track
import dev.nami.player.bluetooth.BluetoothCodecReader

private data class ChainNode(val name: String, val detail: String)

/** План.md §4.6 -- one screen, a vertical chain diagram of exactly what's happening to the
 * audio right now (файл -> декодер -> обработка -> ресемплинг -> вывод), plus a status card
 * explaining why bit-perfect is or isn't active. */
@Composable
fun AudioTractScreen(onBack: () -> Unit, onOpenEqualizer: () -> Unit, viewModel: AudioTractViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val track = uiState.track
    val context = LocalContext.current
    val isBluetoothOutput = remember { BluetoothCodecReader.isBluetoothOutputActive(context) }

    val processingParts = buildList {
        if (uiState.eqEnabled) add("EQ %+.1f/%+.1f/%+.1f дБ".format(uiState.eqBassDb, uiState.eqMidDb, uiState.eqTrebleDb))
        if (uiState.replayGainEnabled) add("ReplayGain")
        if (uiState.ditherEnabled) add("dither")
        if (uiState.crossfadeEnabled) add("кроссфейд")
    }

    val outputDetail = buildString {
        append(if (uiState.bitPerfectUsbEnabled) "USB · bit-perfect (если поддерживается)" else "системный микшер")
        if (isBluetoothOutput) append(" · Bluetooth (кодек недоступен через public API)")
    }

    val nodes = buildList {
        add(ChainNode("Файл", track?.let { formatFileDetail(it) } ?: "ничего не играет"))
        add(ChainNode("Декодер", "нативный, без потерь"))
        add(ChainNode("Обработка", processingParts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "нет"))
        add(ChainNode("Ресемплинг", "нет"))
        add(ChainNode("Вывод", outputDetail))
    }

    val bitPerfectBlockedByEq = uiState.bitPerfectUsbEnabled && uiState.eqEnabled
    val statusText = when {
        bitPerfectBlockedByEq -> "BIT-PERFECT недоступен -- включён EQ, а bit-perfect отключает любую обработку в приложении."
        uiState.bitPerfectUsbEnabled -> "BIT-PERFECT включён -- если ЦАП и его драйвер это реально поддерживают, микширование/ресемплинг/громкость системы для него сейчас пропускаются."
        else -> "Обычный вывод через системный микшер. Bit-perfect и EQ включаются в Настройках."
    }

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(text = "Аудиотракт", color = NamiColors.Paper100, style = MaterialTheme.typography.headlineSmall)
                Column(modifier = Modifier.padding(top = 24.dp)) {
                    nodes.forEachIndexed { index, node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .background(NamiColors.Ink800, RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.5.dp, NamiColors.Ai, RoundedCornerShape(4.dp)),
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(text = node.name, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = node.detail,
                                    color = NamiColors.Paper70,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (index != nodes.lastIndex) {
                            Box(modifier = Modifier.width(1.dp).height(16.dp).background(NamiColors.Ink600).padding(start = 27.dp))
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            if (bitPerfectBlockedByEq) NamiColors.Kin.copy(alpha = 0.12f) else NamiColors.Ink800,
                            RoundedCornerShape(16.dp),
                        )
                        .padding(16.dp),
                ) {
                    Text(
                        text = statusText,
                        color = if (bitPerfectBlockedByEq) NamiColors.Kin else NamiColors.Paper70,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = "Открыть эквалайзер →",
                    color = NamiColors.Shu,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 20.dp).clickable(onClick = onOpenEqualizer),
                )

                Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    ToggleRow("ReplayGain (Beta)", "выравнивает громкость треков, не EBU R128", uiState.replayGainEnabled, viewModel::setReplayGainEnabled)
                    ToggleRow("Dither (Beta)", "сглаживает шум квантования при обработке", uiState.ditherEnabled, viewModel::setDitherEnabled)
                    ToggleRow("Кроссфейд (Beta)", "плавный переход между треками, не настоящее смешивание", uiState.crossfadeEnabled, viewModel::setCrossfadeEnabled)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, caption: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
            Text(caption, color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = NamiColors.Shu, uncheckedTrackColor = NamiColors.Ink600),
        )
    }
}

private fun formatFileDetail(track: Track): String {
    val format = track.format.uppercase()
    val bits = track.bitDepth?.let { "$it бит" }
    val rate = track.sampleRateHz?.let { "${it / 1000} кГц" }
    val channels = when (track.channels) {
        1 -> "моно"
        2 -> "стерео"
        null -> null
        else -> "${track.channels} кан."
    }
    return listOfNotNull(format, bits, rate, channels).joinToString(" · ")
}
