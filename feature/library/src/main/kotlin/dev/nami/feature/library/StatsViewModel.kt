package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.DayActivity
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListeningSummary
import dev.nami.domain.TopTrackStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val WINDOW_DAYS = 120
private const val TOP_WEEK_DAYS = 7
private const val TOP_TRACKS_LIMIT = 5

/** B1 "Статистика" (План.md §23.22) -- first, minimum-scope slice: the contribution grid ("готово"
 * criterion is exactly this one). Weekly-top/heatmap-by-hour are follow-ups, not built yet -- no
 * PlayHistory field exists for time-of-day (only playedAt, which a heatmap could derive later, and
 * no "top tracks this week" query beyond what's already re-derivable from the same table). */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _days = MutableStateFlow<List<DayActivity>>(emptyList())
    val days: StateFlow<List<DayActivity>> = _days.asStateFlow()

    private val _summary = MutableStateFlow(ListeningSummary(0, 0, 0))
    val summary: StateFlow<ListeningSummary> = _summary.asStateFlow()

    private val _topTracksThisWeek = MutableStateFlow<List<TopTrackStat>>(emptyList())
    val topTracksThisWeek: StateFlow<List<TopTrackStat>> = _topTracksThisWeek.asStateFlow()

    private val _hourOfDay = MutableStateFlow<List<Int>>(List(24) { 0 })
    val hourOfDay: StateFlow<List<Int>> = _hourOfDay.asStateFlow()

    init {
        viewModelScope.launch { _days.value = libraryRepository.dailyListeningMinutes(WINDOW_DAYS) }
        viewModelScope.launch { _summary.value = libraryRepository.listeningSummary(WINDOW_DAYS) }
        viewModelScope.launch { _topTracksThisWeek.value = libraryRepository.topTracks(TOP_WEEK_DAYS, TOP_TRACKS_LIMIT) }
        viewModelScope.launch { _hourOfDay.value = libraryRepository.hourOfDayMinutes(WINDOW_DAYS) }
    }
}
