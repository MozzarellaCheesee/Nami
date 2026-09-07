package dev.nami.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.DayActivity
import dev.nami.domain.ListeningSummary
import dev.nami.domain.TopTrackStat
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

private const val WINDOW_DAYS = 120

/** B1 "Статистика" (Design mock 4.8) -- one dark card: header numbers (actually-listened, not
 * library totals), the contribution grid, an hour-of-day bar chart, and this week's top tracks.
 * All Canvas/Compose, no chart library, same approach as the waveform scrubber. */
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val days by viewModel.days.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val topTracks by viewModel.topTracksThisWeek.collectAsState()
    val hourOfDay by viewModel.hourOfDay.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Статистика", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        Column(
            modifier = Modifier
                .padding(20.dp)
                .background(NamiColors.Ink800, RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Text("Статистика", color = NamiColors.Paper100, style = MaterialTheme.typography.headlineSmall)

            SummaryRow(summary, modifier = Modifier.padding(top = 20.dp))

            SectionLabel("Активность", modifier = Modifier.padding(top = 28.dp))
            ContributionGrid(days, modifier = Modifier.padding(top = 12.dp))

            SectionLabel("По времени суток", modifier = Modifier.padding(top = 28.dp))
            HourOfDayChart(hourOfDay, modifier = Modifier.padding(top = 12.dp))

            SectionLabel("Топ недели", modifier = Modifier.padding(top = 28.dp))
            if (topTracks.isEmpty()) {
                Text("Пока нечего показать", color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            } else {
                topTracks.forEachIndexed { index, track -> TopTrackRow(index + 1, track) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = NamiColors.Paper40, style = MaterialTheme.typography.labelMedium, modifier = modifier)
}

@Composable
private fun SummaryRow(summary: ListeningSummary, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        SummaryStat(value = "${summary.totalMinutes / 60} ч", label = "прослушано", modifier = Modifier.weight(1f))
        SummaryStat(value = summary.distinctTracks.toString(), label = "трек", modifier = Modifier.weight(1f))
        SummaryStat(value = summary.distinctArtists.toString(), label = "артистов", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, color = NamiColors.Paper100, style = MaterialTheme.typography.headlineMedium)
        Text(label, color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TopTrackRow(rank: Int, track: TopTrackStat) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "%02d".format(rank),
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(28.dp),
        )
        if (track.albumArtworkPath != null) {
            coil3.compose.AsyncImage(
                model = track.albumArtworkPath,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(40.dp).background(NamiColors.Ink700, RoundedCornerShape(6.dp)),
            )
        } else {
            Box(modifier = Modifier.size(40.dp).background(NamiColors.Ink700, RoundedCornerShape(6.dp)))
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track.artistName?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
    }
}

private val CELL = 14.dp
private val GAP = 3.dp

@Composable
private fun ContributionGrid(days: List<DayActivity>, modifier: Modifier = Modifier) {
    val byDay = remember(days) { days.associateBy { it.epochDay } }
    val today = remember { LocalDate.now().toEpochDay() }
    val weekFields = WeekFields.of(Locale.getDefault())

    // Columns = weeks, rows = day-of-week -- same layout as GitHub's own grid. Oldest day first
    // so the grid reads left-to-right like a timeline.
    val firstDay = today - (WINDOW_DAYS - 1)
    val firstDow = LocalDate.ofEpochDay(firstDay).get(weekFields.dayOfWeek()) - 1
    val totalCells = WINDOW_DAYS + firstDow
    val weeks = (totalCells + 6) / 7

    Box(modifier = modifier.horizontalScroll(rememberScrollState())) {
        Canvas(
            modifier = Modifier
                .width((CELL + GAP) * weeks)
                .height((CELL + GAP) * 7),
        ) {
            val cellPx = CELL.toPx()
            val stridePx = (CELL + GAP).toPx()
            for (cell in 0 until totalCells) {
                val epochDay = firstDay + cell - firstDow
                if (epochDay < firstDay || epochDay > today) continue
                val week = cell / 7
                val dow = cell % 7
                val minutes = byDay[epochDay]?.minutesPlayed ?: 0
                drawRoundRect(
                    color = colorForMinutes(minutes),
                    topLeft = androidx.compose.ui.geometry.Offset(week * stridePx, dow * stridePx),
                    size = androidx.compose.ui.geometry.Size(cellPx, cellPx),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
            }
        }
    }
}

private fun colorForMinutes(minutes: Int) = when {
    minutes <= 0 -> NamiColors.Ink700
    minutes < 15 -> NamiColors.Wakaba.copy(alpha = 0.3f)
    minutes < 30 -> NamiColors.Wakaba.copy(alpha = 0.55f)
    minutes < 60 -> NamiColors.Wakaba.copy(alpha = 0.8f)
    else -> NamiColors.Wakaba
}

/** 24 bars, one per hour of day -- real data (PlayHistory.playedAt hour bucket), not a guessed
 * "night owl" heuristic. Every bar has a visible floor line and every 6th hour is labeled so the
 * chart reads as "time of day" and not just "bars of different heights". */
@Composable
private fun HourOfDayChart(hours: List<Int>, modifier: Modifier = Modifier) {
    val maxMinutes = remember(hours) { (hours.maxOrNull() ?: 0).coerceAtLeast(1) }
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            val barWidth = size.width / hours.size
            // Floor line -- without it a bar for "0 minutes" is invisible (zero height), reading
            // as a gap rather than "nothing happened this hour".
            drawLine(
                color = NamiColors.Ink700,
                start = androidx.compose.ui.geometry.Offset(0f, size.height),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            hours.forEachIndexed { hour, minutes ->
                val barHeight = (size.height - 2.dp.toPx()) * (minutes.toFloat() / maxMinutes)
                drawRoundRect(
                    color = if (minutes > 0) NamiColors.Ai else NamiColors.Ink700,
                    topLeft = androidx.compose.ui.geometry.Offset(hour * barWidth + 1f, size.height - barHeight - 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((barWidth - 2f).coerceAtLeast(1f), barHeight.coerceAtLeast(2.dp.toPx())),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf(0, 6, 12, 18).forEach { hour ->
                Text(
                    text = "%02d:00".format(hour),
                    color = NamiColors.Paper40,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
