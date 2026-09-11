package dev.nami.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.domain.HybridCandidate
import dev.nami.domain.HybridImportItem
import dev.nami.domain.HybridImportProgress
import dev.nami.domain.HybridImportRepository
import dev.nami.domain.HybridTrackStatus
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SpotifyPlaylistAnalysis
import dev.nami.domain.SpotifyPlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SpotifyImportState {
    data object Idle : SpotifyImportState()
    data object Loading : SpotifyImportState()
    data class Result(val analysis: SpotifyPlaylistAnalysis) : SpotifyImportState()
    data class Error(val message: String) : SpotifyImportState()
    data class Assembled(val playlistId: PlaylistId) : SpotifyImportState()
}

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val repository: SpotifyPlaylistRepository,
    private val hybridImportRepository: HybridImportRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val state: StateFlow<SpotifyImportState> = _state

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url

    private val _hybridProgress = MutableStateFlow<HybridImportProgress?>(null)
    val hybridProgress: StateFlow<HybridImportProgress?> = _hybridProgress.asStateFlow()

    private val _reviewItem = MutableStateFlow<HybridImportItem?>(null)
    val reviewItem: StateFlow<HybridImportItem?> = _reviewItem.asStateFlow()

    private var targetPlaylistId: PlaylistId? = null

    val isVkConfigured: Boolean
        get() = !settingsRepository.vkAccessToken.value.isNullOrBlank()

    fun onUrlChange(value: String) {
        _url.value = value
        _state.value = SpotifyImportState.Idle
        _hybridProgress.value = null
    }

    fun analyze() {
        val currentUrl = _url.value
        if (currentUrl.isBlank()) return
        
        _state.value = SpotifyImportState.Loading
        _hybridProgress.value = null
        viewModelScope.launch {
            try {
                val result = repository.analyzePlaylist(currentUrl)
                _state.value = SpotifyImportState.Result(result)
            } catch (e: Exception) {
                _state.value = SpotifyImportState.Error(e.message ?: "Ошибка при анализе")
            }
        }
    }

    fun assemblePlaylist(name: String) {
        val currentState = _state.value
        if (currentState !is SpotifyImportState.Result) return

        viewModelScope.launch {
            try {
                val matchedIds = currentState.analysis.matches
                    .mapNotNull { it.localTrackId }
                
                val playlistId = repository.assemblePlaylist(name, matchedIds)
                _state.value = SpotifyImportState.Assembled(playlistId)
            } catch (e: Exception) {
                _state.value = SpotifyImportState.Error(e.message ?: "Ошибка при создании плейлиста")
            }
        }
    }

    /**
     * Запуск гибридного импорта: скачивание треков, которых нет в локальной библиотеке,
     * через VK Музыку с записью метаданных из Spotify.
     */
    fun startHybridDownload(playlistName: String) {
        val currentState = _state.value
        if (currentState !is SpotifyImportState.Result) return

        viewModelScope.launch {
            try {
                // Создаём плейлист сразу, добавляя треки, которые уже есть локально
                val existingIds = currentState.analysis.matches.mapNotNull { it.localTrackId }
                val createdPlaylistId = repository.assemblePlaylist(playlistName, existingIds)
                targetPlaylistId = createdPlaylistId

                val missingSpotifyTracks = currentState.analysis.matches
                    .filter { it.localTrackId == null }
                    .map { match ->
                        HybridImportItem(
                            spotifyId = match.spotifyTitle + match.spotifyArtist,
                            spotifyTitle = match.spotifyTitle,
                            spotifyArtist = match.spotifyArtist,
                            spotifyAlbum = null,
                            spotifyDurationMs = match.spotifyDurationMs,
                            spotifyCoverUrl = match.spotifyCoverUrl,
                            spotifyYear = null,
                        )
                    }

                hybridImportRepository.startHybridImport(missingSpotifyTracks, createdPlaylistId)
                    .collect { progress ->
                        _hybridProgress.value = progress
                        if (progress.inProgress == 0 && progress.needsReview == 0) {
                            // Все завершены автоматически
                            _state.value = SpotifyImportState.Assembled(createdPlaylistId)
                        }
                    }
            } catch (e: Exception) {
                _state.value = SpotifyImportState.Error(e.message ?: "Ошибка гибридного импорта")
            }
        }
    }

    fun openReviewDialog(item: HybridImportItem) {
        _reviewItem.value = item
    }

    fun dismissReviewDialog() {
        _reviewItem.value = null
    }

    fun resolveCandidate(item: HybridImportItem, candidate: HybridCandidate) {
        _reviewItem.value = null
        viewModelScope.launch {
            val result = hybridImportRepository.resolveCandidateManually(
                item = item,
                chosenCandidate = candidate,
                targetPlaylistId = targetPlaylistId,
            )
            val currentProgress = _hybridProgress.value ?: return@launch
            val updatedItems = currentProgress.items.map {
                if (it.spotifyId == item.spotifyId) {
                    if (result.isSuccess) {
                        it.copy(status = HybridTrackStatus.DONE, localTrackId = result.getOrNull())
                    } else {
                        it.copy(status = HybridTrackStatus.ERROR, errorMessage = result.exceptionOrNull()?.message)
                    }
                } else {
                    it
                }
            }
            val done = updatedItems.count { it.status == HybridTrackStatus.DONE }
            val needsReview = updatedItems.count { it.status == HybridTrackStatus.NEEDS_REVIEW }
            val notFound = updatedItems.count { it.status == HybridTrackStatus.NOT_FOUND || it.status == HybridTrackStatus.ERROR }
            val inProgress = updatedItems.count { it.status == HybridTrackStatus.DOWNLOADING || it.status == HybridTrackStatus.SEARCHING_VK }
            
            _hybridProgress.value = currentProgress.copy(
                done = done,
                needsReview = needsReview,
                notFound = notFound,
                inProgress = inProgress,
                items = updatedItems,
            )

            if (needsReview == 0 && inProgress == 0 && targetPlaylistId != null) {
                _state.value = SpotifyImportState.Assembled(targetPlaylistId!!)
            }
        }
    }
}
