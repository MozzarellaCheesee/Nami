package dev.nami.feature.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistId
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SmartQuery
import dev.nami.domain.SmartRule
import dev.nami.domain.SmartSortField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmartPlaylistEditorUiState(
    val name: String = "",
    val rules: List<SmartRule> = emptyList(),
    val sortBy: SmartSortField = SmartSortField.DATE_ADDED,
    val sortDescending: Boolean = true,
    val limit: Int? = null,
)

/** [existingPlaylistId] is null when creating a new smart playlist (nav arg "playlistId" absent
 * or blank), matching the plan's own `SmartPlaylistEditor(playlistId?)` route -- editing an
 * existing one loads its current query once and Save calls updateSmartQuery instead of create. */
@HiltViewModel
class SmartPlaylistEditorViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val existingPlaylistId = savedStateHandle.get<String>("playlistId")?.takeIf { it.isNotBlank() }?.let(::PlaylistId)

    private val _uiState = MutableStateFlow(SmartPlaylistEditorUiState())
    val uiState: StateFlow<SmartPlaylistEditorUiState> = _uiState.asStateFlow()

    init {
        val id = existingPlaylistId
        if (id != null) {
            viewModelScope.launch {
                playlistRepository.playlist(id).collect { playlist ->
                    if (playlist == null) return@collect
                    val query = playlist.smartQueryJson?.let(playlistRepository::parseSmartQuery)
                    _uiState.value = SmartPlaylistEditorUiState(
                        name = playlist.name,
                        rules = query?.rules ?: emptyList(),
                        sortBy = query?.sortBy ?: SmartSortField.DATE_ADDED,
                        sortDescending = query?.sortDescending ?: true,
                        limit = query?.limit,
                    )
                }
            }
        }
    }

    fun setName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun addRule(rule: SmartRule) {
        _uiState.value = _uiState.value.copy(rules = _uiState.value.rules + rule)
    }

    fun removeRule(index: Int) {
        _uiState.value = _uiState.value.copy(rules = _uiState.value.rules.filterIndexed { i, _ -> i != index })
    }

    fun setSortBy(field: SmartSortField) {
        _uiState.value = _uiState.value.copy(sortBy = field)
    }

    fun setSortDescending(descending: Boolean) {
        _uiState.value = _uiState.value.copy(sortDescending = descending)
    }

    fun setLimit(limit: Int?) {
        _uiState.value = _uiState.value.copy(limit = limit)
    }

    fun applyPreset(rules: List<SmartRule>, sortBy: SmartSortField, sortDescending: Boolean, limit: Int?) {
        _uiState.value = _uiState.value.copy(rules = rules, sortBy = sortBy, sortDescending = sortDescending, limit = limit)
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank() || state.rules.isEmpty()) return
        val query = SmartQuery(state.rules, state.sortBy, state.sortDescending, state.limit)
        viewModelScope.launch {
            val id = existingPlaylistId
            if (id != null) {
                playlistRepository.renamePlaylist(id, state.name)
                playlistRepository.updateSmartQuery(id, query)
            } else {
                playlistRepository.createSmartPlaylist(state.name, query)
            }
            onSaved()
        }
    }
}
