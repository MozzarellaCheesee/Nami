package dev.nami.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.player.eq.EqPreset
import dev.nami.player.eq.ParametricEqAudioProcessor
import kotlin.math.ln

private val BAND_FREQS_HZ = ParametricEqAudioProcessor.BAND_FREQS_HZ

private fun freqLabel(freqHz: Float): String =
    if (freqHz >= 1000f) "${(freqHz / 1000f).toInt()} кГц" else "${freqHz.toInt()} Гц"

/** План.md §4.7 -- 9-band graphic EQ (63Hz..16kHz), a real gain curve drawn from the actual band
 * values, six fixed presets plus an auto-detected "Пользовательский" state, and a card per band
 * with a live slider. */
@Composable
fun EqualizerScreen(onBack: () -> Unit, viewModel: AudioTractViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val eqEnabled = uiState.eqEnabled
    val gains = uiState.eqBandGains
    val activePreset = EqPreset.matching(gains)

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
                }
                Text(text = "Эквалайзер", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = { viewModel.setEqEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = NamiColors.Shu,
                        checkedThumbColor = NamiColors.Paper100,
                        uncheckedTrackColor = NamiColors.Ink600,
                        uncheckedThumbColor = NamiColors.Paper70,
                    ),
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                EqCurve(gains = gains, modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("63 Гц", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
                    Text("1 кГц", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
                    Text("16 кГц", color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 24.dp),
                ) {
                    EqPreset.entries.forEach { preset ->
                        PresetPill(preset.label, selected = activePreset == preset) {
                            viewModel.setEqBandGains(preset.gainsDb)
                        }
                    }
                    PresetPill("Пользовательский", selected = activePreset == null, enabled = false, onClick = {})
                }

                gains.forEachIndexed { index, gainDb ->
                    val freqHz = BAND_FREQS_HZ[index]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .background(NamiColors.Ink800, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = freqLabel(freqHz),
                                color = NamiColors.Paper100,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(
                                text = "%+.1f дБ".format(gainDb),
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Slider(
                            value = gainDb,
                            onValueChange = { value ->
                                viewModel.setEqBandGains(gains.toMutableList().also { it[index] = value })
                            },
                            valueRange = -12f..12f,
                            colors = SliderDefaults.colors(thumbColor = NamiColors.Shu, activeTrackColor = NamiColors.Shu, inactiveTrackColor = NamiColors.Ink600),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PresetPill(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) NamiColors.Paper100 else NamiColors.Ink800, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = when {
                selected -> NamiColors.Ink900
                enabled -> NamiColors.Paper70
                else -> NamiColors.Paper40
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Real frequency-response curve, not decorative -- sums all 9 peaking bands' actual gain at each
 * drawn frequency (log-spaced 20Hz..20kHz), same bell-shape math the DSP itself uses. */
@Composable
private fun EqCurve(gains: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val maxDb = 12f

        fun gainAt(freqHz: Float): Float =
            BAND_FREQS_HZ.indices.sumOf { i ->
                // Same bell curve shape a peaking biquad traces (Q-scaled Gaussian in log-freq
                // space) -- not the exact same numbers a biquad's actual frequency response would
                // show, but the same visual shape for the same reason: it peaks at the band's
                // center frequency and falls off symmetrically in octaves either side.
                val octaves = ln(freqHz / BAND_FREQS_HZ[i]) / ln(2f)
                val bellWidth = 0.7
                (gains[i] * kotlin.math.exp(-(octaves * octaves) / (2 * bellWidth * bellWidth))).toDouble()
            }.toFloat()

        fun xFor(freqHz: Float): Float {
            val logMin = ln(20f)
            val logMax = ln(20000f)
            return w * (ln(freqHz) - logMin) / (logMax - logMin)
        }

        fun yFor(gainDb: Float): Float = midY - (gainDb / maxDb) * midY

        listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
            drawLine(NamiColors.Ink700, Offset(0f, h * fraction), Offset(w, h * fraction), strokeWidth = 1.5f)
        }

        val path = Path()
        val fillPath = Path()
        var first = true
        var lastX = 0f
        val steps = 120
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val logMin = ln(20f)
            val logMax = ln(20000f)
            val freq = kotlin.math.exp(logMin + t * (logMax - logMin))
            val x = xFor(freq)
            val y = yFor(gainAt(freq))
            if (first) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
        }
        fillPath.lineTo(lastX, h)
        fillPath.close()

        drawPath(fillPath, color = NamiColors.Shu.copy(alpha = 0.10f))
        drawPath(path, color = NamiColors.Shu, style = Stroke(width = 4f, cap = StrokeCap.Round))

        BAND_FREQS_HZ.forEachIndexed { i, freqHz ->
            val x = xFor(freqHz)
            val y = yFor(gains[i])
            drawCircle(NamiColors.Paper100, radius = 6f, center = Offset(x, y))
            drawCircle(NamiColors.Ink900, radius = 6f, center = Offset(x, y), style = Stroke(width = 2.5f))
        }
    }
}
