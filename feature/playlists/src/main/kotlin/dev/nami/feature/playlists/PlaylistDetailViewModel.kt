package dev.nami.feature.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(val playlist: Playlist? = null, val tracks: List<Track> = emptyList())

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    savedStateHandle: SavedStateHandle,
    // Nullable с дефолтом по той же причине, что и в NowPlayingViewModel: нужны только двум
    // новым действиям (цепочки и запоминание эквалайзера), а существующие тесты продолжают
    // строить этот ViewModel одним репозиторием и SavedStateHandle.
    private val libraryRepository: dev.nami.domain.LibraryRepository? = null,
    private val settingsRepository: dev.nami.domain.SettingsRepository? = null,
    private val serverLibraryRepository: dev.nami.domain.ServerLibraryRepository? = null,
) : ViewModel() {

    val playlistId = PlaylistId(checkNotNull(savedStateHandle.get<String>("playlistId")))

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private val _guestLink = MutableStateFlow<String?>(null)
    /** URL созданной гостевой ссылки - экран отправляет его в системный share-sheet. */
    val guestLink: StateFlow<String?> = _guestLink.asStateFlow()
    fun clearGuestLink() { _guestLink.value = null }

    private val _shareError = MutableStateFlow<String?>(null)
    val shareError: StateFlow<String?> = _shareError.asStateFlow()
    fun clearShareError() { _shareError.value = null }

    fun serverActive(): Boolean = serverLibraryRepository?.isServerActive() == true

    /** Создаёт гостевую ссылку на треки плейлиста, что нашлись на сервере. */
    fun createGuestLink() {
        val repo = serverLibraryRepository ?: return
        val state = _uiState.value
        val tracks = state.tracks
        if (tracks.isEmpty()) {
            _shareError.value = "Плейлист пуст"
            return
        }
        viewModelScope.launch {
            val link = repo.createGuestLink(
                title = state.playlist?.name ?: "Плейлист из NAMI",
                tracks = tracks.map { Triple(it.artistName, it.title, it.durationMs) },
            )
            if (link != null) _guestLink.value = link
            else _shareError.value = "Ни один трек плейлиста не найден на сервере"
        }
    }

    init {
        playlistRepository.playlist(playlistId)
            .combine(playlistRepository.tracksInPlaylist(playlistId)) { playlist, tracks ->
                PlaylistDetailUiState(playlist = playlist, tracks = tracks)
            }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun removeTrack(trackId: TrackId) {
        viewModelScope.launch { playlistRepository.removeTrack(playlistId, trackId) }
    }

    fun rename(name: String) {
        viewModelScope.launch { playlistRepository.renamePlaylist(playlistId, name) }
    }

    /** П.md §20 - свои настройки плейлиста. Пишутся все три разом (см. PlaylistDao). */
    private fun setPlaybackSettings(eqGainsCsv: String?, crossfadeEnabled: Boolean?, shuffleOnStart: Boolean?) {
        viewModelScope.launch {
            playlistRepository.setPlaybackSettings(playlistId, eqGainsCsv, crossfadeEnabled, shuffleOnStart)
        }
    }

    /** Эквалайзер запоминается снимком текущих полос, а не именем пресета: имени в хранилище нет
     * нигде, пресет опознаётся по значениям, и "как сейчас звучит" - ровно то, что пользователь
     * в этот момент видит на ползунках. */
    fun rememberCurrentEq() {
        val playlist = _uiState.value.playlist ?: return
        val csv = (settingsRepository ?: return).eqBandGains.value.joinToString(",")
        setPlaybackSettings(csv, playlist.crossfadeEnabled, playlist.shuffleOnStart)
    }

    fun clearEq() {
        val playlist = _uiState.value.playlist ?: return
        setPlaybackSettings(null, playlist.crossfadeEnabled, playlist.shuffleOnStart)
    }

    /** null -> вкл -> выкл -> null. Третье состояние ("не задано") обязано быть достижимым:
     * без него настройку нельзя снять, только перевести в "всегда выключать". */
    fun cycleCrossfade() {
        val playlist = _uiState.value.playlist ?: return
        val next = when (playlist.crossfadeEnabled) {
            null -> true
            true -> false
            false -> null
        }
        setPlaybackSettings(playlist.eqGainsCsv, next, playlist.shuffleOnStart)
    }

    fun cycleShuffleOnStart() {
        val playlist = _uiState.value.playlist ?: return
        val next = when (playlist.shuffleOnStart) {
            null -> true
            true -> false
            false -> null
        }
        setPlaybackSettings(playlist.eqGainsCsv, playlist.crossfadeEnabled, next)
    }

    /** П.md §20 "цепочки" - "после [trackId] всегда ставь [nextTrackId]". null снимает звено. */
    fun setChain(trackId: TrackId, nextTrackId: TrackId?) {
        viewModelScope.launch { libraryRepository?.setTrackChain(trackId, nextTrackId) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
            onDeleted()
        }
    }
}
