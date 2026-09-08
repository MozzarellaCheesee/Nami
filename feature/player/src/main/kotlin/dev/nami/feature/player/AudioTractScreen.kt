package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.model.Track
import dev.nami.player.output.AudioOutputInfo

private data class ChainNode(val name: String, val detail: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** План.md §4.6 - one screen, a vertical chain diagram of exactly what's happening to the
 * audio right now (файл -> декодер -> обработка -> ресемплинг -> вывод), plus a status card
 * explaining why bit-perfect is or isn't active. Thin wrapper around [AudioTractBody] - the
 * standalone full-screen route (status bar padding + real back navigation). */
@Composable
fun AudioTractScreen(onBack: () -> Unit, onOpenEqualizer: () -> Unit, viewModel: AudioTractViewModel = hiltViewModel()) {
    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
                }
            }
            AudioTractBody(onOpenEqualizer = onOpenEqualizer, viewModel = viewModel)
        }
    }
}

/** The actual content, no outer screen chrome (no back button/status-bar padding) - reused
 * as-is both by [AudioTractScreen] (the full-screen route) and by NowPlayingScreen's overflow
 * menu, which shows this same content inline in a bottom sheet instead of navigating away. */
@Composable
fun AudioTractBody(onOpenEqualizer: () -> Unit, viewModel: AudioTractViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val track = uiState.track
    val context = LocalContext.current
    // Импорт импульса через SAF. "*/*" в списке типов не от лени: IR-файлы часто отдаются
    // провайдерами с типом application/octet-stream, и без него они были бы недоступны для выбора.
    val pickImpulseResponse = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importImpulseResponse) }
    val irImportError by viewModel.irImportError.collectAsState()
    androidx.compose.runtime.LaunchedEffect(irImportError) {
        val message = irImportError ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.irImportErrorShown()
    }
    val isBluetoothOutput by rememberBluetoothOutputActive()
    val outputSampleRateHz = remember { AudioOutputInfo.outputSampleRateHz(context) }

    // Hi-Fi keeps the DSP sink out of the path entirely (PlaybackService.currentNeedsCustomSink),
    // so the chain diagram has to say so rather than listing effects that aren't running.
    val processingParts = buildList {
        if (uiState.hiFiEnabled) return@buildList
        if (uiState.eqEnabled) {
            val presetLabel = dev.nami.player.eq.EqPreset.matching(uiState.eqBandGains)?.label ?: "Пользовательский"
            add("EQ ($presetLabel)")
        }
        if (uiState.replayGainEnabled) add("ReplayGain")
        if (uiState.ditherEnabled) add("dither")
        if (uiState.playbackGainDb != 0f) add("усиление +${uiState.playbackGainDb.toInt()} дБ")
        if (uiState.crossfadeEnabled) add("кроссфейд")
    }

    // Real answer instead of a hardcoded "нет": AudioFlinger resamples whenever the file's rate
    // isn't the mixer's rate, and that's the one part of it a normal app can actually read.
    val trackSampleRateHz = track?.sampleRateHz
    val resamplingDetail = when {
        outputSampleRateHz == null -> "неизвестно (система не сообщает частоту вывода)"
        trackSampleRateHz == null -> "выход ${outputSampleRateHz / 1000} кГц"
        trackSampleRateHz == outputSampleRateHz -> "нет · ${outputSampleRateHz / 1000} кГц"
        else -> "${trackSampleRateHz / 1000} → ${outputSampleRateHz / 1000} кГц (системный микшер)"
    }

    val outputDetail = buildString {
        append(if (uiState.bitPerfectUsbEnabled) "USB · bit-perfect (если поддерживается)" else "системный микшер")
        if (isBluetoothOutput) append(" · Bluetooth (кодек недоступен через public API)")
    }

    val nodes = buildList {
        add(ChainNode("Файл", track?.let { formatFileDetail(it) } ?: "ничего не играет", Icons.Outlined.Description))
        add(ChainNode("Декодер", track?.let { decoderDetail(it) } ?: "нативный", Icons.Outlined.Memory))
        val processingDetail = processingParts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            ?: if (uiState.hiFiEnabled) "нет · Hi-Fi (прямой тракт)" else "нет"
        add(ChainNode("Обработка", processingDetail, Icons.Outlined.Tune))
        add(ChainNode("Ресемплинг", resamplingDetail, Icons.Outlined.CompareArrows))
        add(ChainNode("Вывод", outputDetail, Icons.Outlined.Speaker))
    }

    val bitPerfectBlockedByEq = uiState.bitPerfectUsbEnabled && uiState.eqEnabled && !uiState.hiFiEnabled
    val statusText = when {
        bitPerfectBlockedByEq -> "BIT-PERFECT недоступен - включён EQ, а bit-perfect отключает любую обработку в приложении."
        uiState.hiFiEnabled -> "HI-FI включён - Nami не трогает сэмплы: EQ/ReplayGain/dither/усиление обходятся, декодированный поток идёт в AudioTrack как есть. Системный микшер (и его ресемплинг) это не отменяет - за это отвечает только bit-perfect по USB, и только если железо его тянет."
        uiState.bitPerfectUsbEnabled -> "BIT-PERFECT включён - если ЦАП и его драйвер это реально поддерживают, микширование/ресемплинг/громкость системы для него сейчас пропускаются."
        else -> "Обычный вывод через системный микшер. Bit-perfect и EQ включаются в Настройках."
    }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(text = "Аудиотракт", color = NamiColors.Paper100, style = MaterialTheme.typography.headlineSmall)
                Column(modifier = Modifier.padding(top = 24.dp)) {
                    nodes.forEachIndexed { index, node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(node.icon, contentDescription = null, tint = NamiColors.Ai, modifier = Modifier.size(20.dp))
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
                            Box(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(NamiColors.Ink600),
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            if (bitPerfectBlockedByEq) NamiColors.Kin.copy(alpha = 0.12f) else NamiColors.Ink800,
                            RoundedCornerShape(NamiRadius.Card),
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
                    ToggleRow("Hi-Fi (Beta)", "прямой тракт: обходит EQ/ReplayGain/dither/усиление, ничего не считает по сэмплам", uiState.hiFiEnabled, viewModel::setHiFiEnabled)
                    ToggleRow("Bit-perfect по USB (Android 14+, Beta)", "выключает EQ/ReplayGain/dither/кроссфейд, если реально включился", uiState.bitPerfectUsbEnabled, viewModel::setBitPerfectUsbEnabled)
                    ToggleRow("ReplayGain (Beta)", "выравнивает громкость треков, не EBU R128", uiState.replayGainEnabled, viewModel::setReplayGainEnabled)
                    ToggleRow("Dither (Beta)", "сглаживает шум квантования при обработке", uiState.ditherEnabled, viewModel::setDitherEnabled)
                    ToggleRow("Кроссфид (Beta)", "подмешивает каналы с задержкой 0.3 мс - собирает разваленную в наушниках сцену к центру, только для стерео", uiState.crossfeedEnabled, viewModel::setCrossfeedEnabled)
                    ToggleRow("Кроссфейд (Beta)", "плавный переход между треками, не настоящее смешивание", uiState.crossfadeEnabled, viewModel::setCrossfadeEnabled)
                    if (uiState.crossfadeEnabled) {
                        ToggleRow(
                            "Умный кроссфейд (Beta)",
                            "не применять переход, если трек резко обрывается на пике громкости",
                            uiState.smartCrossfadeEnabled,
                            viewModel::setSmartCrossfadeEnabled,
                        )
                    }
                }

                Text(
                    text = "Усиление воспроизведения",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GainPill("Выкл", selected = uiState.playbackGainDb == 0f) { viewModel.setPlaybackGainDb(0f) }
                    GainPill("+3 дБ", selected = uiState.playbackGainDb == 3f) { viewModel.setPlaybackGainDb(3f) }
                    GainPill("+6 дБ", selected = uiState.playbackGainDb == 6f) { viewModel.setPlaybackGainDb(6f) }
                }

                Text(
                    text = "Профили по устройству вывода",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                ToggleRow(
                    "Профили по устройству (Beta)",
                    "свой EQ и лимит громкости для проводного/BT/USB-ЦАП/динамика - заменяет ручной EQ, пока активен",
                    uiState.outputProfilesEnabled,
                    viewModel::setOutputProfilesEnabled,
                )
                if (uiState.outputProfilesEnabled) {
                    dev.nami.domain.OutputDeviceType.entries.forEach { type ->
                        OutputProfileRow(
                            type = type,
                            profile = uiState.outputProfiles[type] ?: dev.nami.domain.OutputProfile.IDENTITY,
                            onProfileChange = { viewModel.setOutputProfile(type, it) },
                        )
                    }
                }
                Text(
                    text = "Свёртка с импульсом комнаты",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                ToggleRow(
                    "Свёртка с IR (Beta)",
                    "накладывает импульсную характеристику помещения или наушников, добавляет ~46 мс задержки",
                    uiState.convolutionEnabled,
                    viewModel::setConvolutionEnabled,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
                        .clickable { pickImpulseResponse.launch(arrayOf("audio/x-wav", "audio/wav", "*/*")) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Файл импульса", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = uiState.convolutionIrPath?.substringAfterLast('/') ?: "не выбран - нажми, чтобы выбрать WAV",
                            color = NamiColors.Paper40,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = NamiColors.Paper40)
                }

                Text(
                    text = "Тест устройства",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
                        .clickable(enabled = !uiState.deviceProbeRunning) { viewModel.runDeviceProbe() }
                        .padding(16.dp),
                ) {
                    Text(
                        text = if (uiState.deviceProbeRunning) "Проверяю..." else "Проверить, какие форматы принимает выход",
                        color = if (uiState.deviceProbeRunning) NamiColors.Paper40 else NamiColors.Shu,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    uiState.deviceAudioProfile?.let { profile ->
                        Text(
                            text = profile,
                            color = NamiColors.Paper70,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        // Без этой оговорки таблица читается как «мой ЦАП играет 192 кГц», чего
                        // она не означает - см. DeviceAudioProbe.
                        Text(
                            text = "Это то, что примет микшер Android, а не обязательно то, что уйдёт в железо без пересчёта.",
                            color = NamiColors.Paper40,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
}

@Composable
private fun ToggleRow(title: String, caption: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
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
            colors = SwitchDefaults.colors(
                checkedTrackColor = NamiColors.Shu,
                checkedThumbColor = NamiColors.Paper100,
                uncheckedTrackColor = NamiColors.Ink600,
                uncheckedThumbColor = NamiColors.Paper70,
            ),
        )
    }
}

private fun deviceTypeLabel(type: dev.nami.domain.OutputDeviceType): String = when (type) {
    dev.nami.domain.OutputDeviceType.WIRED -> "Проводные наушники"
    dev.nami.domain.OutputDeviceType.BLUETOOTH -> "Bluetooth"
    dev.nami.domain.OutputDeviceType.USB_DAC -> "USB-ЦАП"
    dev.nami.domain.OutputDeviceType.SPEAKER -> "Динамик"
}

/** One collapsible per-device profile card: a preset picker (reusing the same fixed EqPreset set
 * as the main equalizer - a full 9-slider editor per device would be a lot of chrome for a
 * feature most people set once and forget) plus a volume ceiling slider. */
@Composable
private fun OutputProfileRow(
    type: dev.nami.domain.OutputDeviceType,
    profile: dev.nami.domain.OutputProfile,
    onProfileChange: (dev.nami.domain.OutputProfile) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val activePreset = dev.nami.player.eq.EqPreset.matching(profile.eqGainsDb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(deviceTypeLabel(type), color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = (activePreset?.label ?: "Пользовательский") + " · громкость ${profile.volumeLimitPercent}%",
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (expanded) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                dev.nami.player.eq.EqPreset.entries.forEach { preset ->
                    PresetPillCompact(preset.label, selected = activePreset == preset) {
                        onProfileChange(profile.copy(eqGainsDb = preset.gainsDb))
                    }
                }
            }
            Text(
                text = "Лимит громкости: ${profile.volumeLimitPercent}%",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            androidx.compose.material3.Slider(
                value = profile.volumeLimitPercent.toFloat(),
                onValueChange = { onProfileChange(profile.copy(volumeLimitPercent = it.toInt())) },
                valueRange = 10f..100f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = NamiColors.Shu,
                    activeTrackColor = NamiColors.Shu,
                    inactiveTrackColor = NamiColors.Ink600,
                ),
            )
        }
    }
}

@Composable
private fun PresetPillCompact(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) NamiColors.Paper100 else NamiColors.Ink700, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) NamiColors.Ink900 else NamiColors.Paper70,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun GainPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) NamiColors.Paper100 else NamiColors.Ink800, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) NamiColors.Ink900 else NamiColors.Paper70,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// Real per-format info, not a static label - codec name (from the file's own format) plus
// whether it's lossless, both actually true facts about what's playing right now.
private val LOSSLESS_FORMATS = setOf("flac", "wav", "alac", "ape", "wv", "tak", "aiff", "dsf", "dff")

private fun decoderDetail(track: Track): String {
    val format = track.format.lowercase()
    val codecName = track.format.uppercase()
    return if (format in LOSSLESS_FORMATS) "$codecName, нативный, без потерь" else "$codecName, нативный, с потерями"
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
