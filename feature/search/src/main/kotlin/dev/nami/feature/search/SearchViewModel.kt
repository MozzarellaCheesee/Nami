package dev.nami.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.SearchRepository
import dev.nami.domain.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val recentQueries: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    // In-memory only (not persisted) -- a nice-to-have recall of this session's own searches,
    // not a durable history feature (Дизайн.md's mock shows it as a light convenience, not
    // something worth a DB table + migration for).
    private val recentQueries = LinkedHashSet<String>()
    private companion object {
        const val MAX_RECENT = 8
    }

    init {
        queryFlow
            .debounce(200)
            .mapLatest { query ->
                if (query.isBlank()) emptyList() else searchRepository.search(query)
            }
            .onEach { results ->
                _uiState.value = _uiState.value.copy(results = results)
                // Records a search once it actually found something, debounced (200ms of no
                // typing) -- not on every keystroke, which would fill the list with fragments
                // instead of finished searches.
                if (results.isNotEmpty()) rememberQuery(_uiState.value.query)
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        queryFlow.value = query
    }

    private fun rememberQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        recentQueries.remove(trimmed)
        recentQueries.add(trimmed)
        while (recentQueries.size > MAX_RECENT) recentQueries.remove(recentQueries.first())
        _uiState.value = _uiState.value.copy(recentQueries = recentQueries.toList().asReversed())
    }

    fun onRecentQueryClick(query: String) {
        onQueryChange(query)
    }
}
