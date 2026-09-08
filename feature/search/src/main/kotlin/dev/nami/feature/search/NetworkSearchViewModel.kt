package dev.nami.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.NetworkImportRepository
import dev.nami.domain.NetworkImportSource
import dev.nami.domain.NetworkTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkSearchUiState(
    val source: NetworkImportSource = NetworkImportSource.AUDIUS,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<NetworkTrack> = emptyList(),
    /** Отличает "ещё не искали" от "искали и ничего не нашли" - иначе пустой экран врёт. */
    val searched: Boolean = false,
    val downloading: Set<String> = emptySet(),
    val imported: Set<String> = emptySet(),
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class NetworkSearchViewModel @Inject constructor(
    private val repository: NetworkImportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkSearchUiState())
    val uiState: StateFlow<NetworkSearchUiState> = _uiState.asStateFlow()

    private val requests = MutableStateFlow(NetworkImportSource.AUDIUS to "")

    init {
        requests
            // Секунда, а не привычные 200 мс: каждый символ здесь - реальный запрос в чужой API,
            // а не выборка из локальной базы.
            .debounce(700)
            .onEach { (_, query) -> if (query.isNotBlank()) _uiState.value = _uiState.value.copy(loading = true) }
            .mapLatest { (source, query) ->
                if (query.isBlank()) Triple(source, query, emptyList()) else Triple(source, query, repository.search(source, query))
            }
            .onEach { (source, query, results) ->
                // Ответ мог прийти уже после смены источника/запроса - тогда он не про то, что на
                // экране сейчас, и показывать его нельзя.
                val state = _uiState.value
                if (state.source != source || state.query.trim() != query.trim()) return@onEach
                _uiState.value = state.copy(loading = false, results = results, searched = query.isNotBlank())
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query, message = null)
        requests.value = _uiState.value.source to query
    }

    fun onSourceChange(source: NetworkImportSource) {
        _uiState.value = _uiState.value.copy(source = source, results = emptyList(), searched = false, message = null)
        requests.value = source to _uiState.value.query
    }

    fun onDownload(track: NetworkTrack) {
        val key = trackKey(track)
        if (key in _uiState.value.downloading || key in _uiState.value.imported) return
        _uiState.value = _uiState.value.copy(downloading = _uiState.value.downloading + key, message = null)
        viewModelScope.launch {
            val error = repository.importTrack(track)
            _uiState.value = _uiState.value.let { state ->
                state.copy(
                    downloading = state.downloading - key,
                    imported = if (error == null) state.imported + key else state.imported,
                    message = error,
                )
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

internal fun trackKey(track: NetworkTrack): String = "${track.source}:${track.id}"
