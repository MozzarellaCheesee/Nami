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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.DayActivity
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** B1 "Статистика" -- GitHub-style contribution grid, real data from play_history, no chart
 * library (same Canvas-only approach as the waveform scrubber). */
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val days by viewModel.days.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Статистика", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        val totalMinutes = days.sumOf { it.minutesPlayed }
        Text(
            "$totalMinutes мин прослушано за последние 120 дней",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )

        ContributionGrid(days, modifier = Modifier.padding(20.dp))
    }
}

private const val WINDOW_DAYS = 120
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
