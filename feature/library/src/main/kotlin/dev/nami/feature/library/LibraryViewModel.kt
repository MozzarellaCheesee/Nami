package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.Artist
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.TagRepository
import dev.nami.domain.TrackSort
import dev.nami.domain.TrashRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LibraryTab { TRACKS, ALBUMS, ARTISTS, GENRES, FOLDERS, TAGS, YEARS }

/** Вкладки, которые показывают не плоский список, а сначала список групп (жанр/папка/тег/год),
 * и уже внутри группы - треки. */
val LibraryTab.isBrowse: Boolean
    get() = this == LibraryTab.GENRES || this == LibraryTab.FOLDERS ||
        this == LibraryTab.TAGS || this == LibraryTab.YEARS

data class NowPlayingRow(val trackId: TrackId, val isPlaying: Boolean)

/** Одна строка в списке групп вкладок Жанры/Папки/Теги/Годы. */
data class BrowseGroup(val key: String, val title: String, val trackCount: Int)

data class LibraryUiState(
    val importProgress: ImportProgress? = null,
    val selectedTab: LibraryTab = LibraryTab.TRACKS,
    val lastDeletedTrackIds: Set<TrackId> = emptySet(),
    val selectedTrackIds: Set<TrackId> = emptySet(),
    val selectedAlbumIds: Set<AlbumId> = emptySet(),
    val sort: TrackSort = TrackSort.DATE_ADDED,
    val browseGroups: List<BrowseGroup> = emptyList(),
    val browseLoading: Boolean = false,
    /** Открытая группа внутри browse-вкладки; null - показываем список групп. */
    val openedGroup: BrowseGroup? = null,
    val openedGroupTracks: List<Track> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
    private val trashRepository: TrashRepository,
    private val playerRepository: PlayerRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val tagRepository: TagRepository,
    private val serverLibraryRepository: dev.nami.domain.ServerLibraryRepository,
    private val serverAudioRepository: dev.nami.domain.ServerAudioRepository,
    jamRepository: dev.nami.domain.JamRepository,
) : ViewModel() {

    private val _serverActionMsg = MutableStateFlow<String?>(null)
    /** Итог последнего действия «Отправить на сервер» - экран показывает Snackbar. */
    val serverActionMsg: StateFlow<String?> = _serverActionMsg
    fun clearServerActionMsg() { _serverActionMsg.value = null }

    private val _serverTracks = MutableStateFlow<List<dev.nami.domain.ServerTrackMeta>>(emptyList())
    val serverTracks: StateFlow<List<dev.nami.domain.ServerTrackMeta>> = _serverTracks.asStateFlow()

    /** Кешированный снимок локальных треков для быстрой синхронной дедупликации с сервером. */
    @Volatile private var localTracksSnapshot: List<dev.nami.core.model.Track> = emptyList()

    init {
        viewModelScope.launch {
            serverLibraryRepository.uploadProgress.collect { msg ->
                if (msg != null && msg.startsWith("Выгрузка завершена")) {
                    _serverActionMsg.value = msg
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            localTracksSnapshot = libraryRepository.allTracksOrdered()
        }
        refreshServerTracks()
        jamRepository.serverChanges
            .onEach { entities -> if ("tracks" in entities) refreshServerTracks() }
            .launchIn(viewModelScope)
    }

    fun refreshServerTracks() {
        if (!isServerActive()) return
        viewModelScope.launch(Dispatchers.IO) {
            localTracksSnapshot = libraryRepository.allTracksOrdered()
            val tracks = serverLibraryRepository.listTracks()
            if (tracks != null) {
                _serverTracks.value = tracks
            }
        }
    }

    fun isLocallyAvailable(serverTrack: dev.nami.domain.ServerTrackMeta): Boolean {
        val title = serverTrack.title.trim().lowercase()
        val artist = serverTrack.artist.trim().lowercase()
        val duration = serverTrack.durationMs
        return localTracksSnapshot.any { local ->
            local.title.trim().lowercase() == title &&
            (local.artistName?.trim()?.lowercase() ?: "") == artist &&
            kotlin.math.abs(local.durationMs - duration) <= 2000
        }
    }

    fun playServerTrack(track: dev.nami.domain.ServerTrackMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            val streamUrl = serverAudioRepository.serverStreamUrl(track.id) ?: return@launch
            val artworkUrl = serverAudioRepository.serverArtworkUrl(track.id)
            val playable = dev.nami.domain.PlayableTrack(
                id = dev.nami.core.model.TrackId("server_${track.id}"),
                title = track.title,
                artistName = track.artist,
                path = streamUrl,
                artworkPath = artworkUrl,
                durationMs = track.durationMs,
            )
            withContext(Dispatchers.Main) {
                playerRepository.play(listOf(playable), startIndex = 0)
            }
        }
    }

    fun playAllServerTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            val notLocal = _serverTracks.value.filter { !isLocallyAvailable(it) }
            val playables = notLocal.mapNotNull { track ->
                val streamUrl = serverAudioRepository.serverStreamUrl(track.id) ?: return@mapNotNull null
                val artworkUrl = serverAudioRepository.serverArtworkUrl(track.id)
                dev.nami.domain.PlayableTrack(
                    id = dev.nami.core.model.TrackId("server_${track.id}"),
                    title = track.title,
                    artistName = track.artist,
                    path = streamUrl,
                    artworkPath = artworkUrl,
                    durationMs = track.durationMs,
                )
            }
            if (playables.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    playerRepository.play(playables, startIndex = 0)
                }
            }
        }
    }

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult
    fun clearImportResult() { _importResult.value = null }

    /** Виден ли пункт меню «Отправить на сервер». */
    fun isServerActive(): Boolean = serverLibraryRepository.isServerActive()

    fun uploadTrackToServer(track: Track) {
        serverLibraryRepository.uploadTracksBackground(listOf(track))
        _serverActionMsg.value = "Отправка трека на сервер…"
    }

    fun selectAllTracks() {
        viewModelScope.launch {
            val allIds = libraryRepository.allTracksOrdered().map { it.id }.toSet()
            _uiState.value = _uiState.value.copy(selectedTrackIds = allIds)
        }
    }

    fun uploadSelectedTracksToServer() {
        val ids = uiState.value.selectedTrackIds.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = ids.mapNotNull { id -> libraryRepository.track(id).first() }
            if (tracks.isNotEmpty()) {
                serverLibraryRepository.uploadTracksBackground(tracks)
                _serverActionMsg.value = "Отправка ${tracks.size} треков на сервер…"
            }
        }
    }

    fun likeTrack(trackId: TrackId) {
        viewModelScope.launch { playlistRepository.likeTrack(trackId) }
    }

    fun likeSelectedTracks() {
        val ids = uiState.value.selectedTrackIds
        viewModelScope.launch { ids.forEach { playlistRepository.likeTrack(it) } }
        clearSelection()
    }

    fun batchEditSelectedTracks(artistName: String?, albumName: String?, year: Int?, genre: String?) {
        val ids = uiState.value.selectedTrackIds.toList()
        clearSelection()
        batchEditTracks(ids, artistName, albumName, year, genre)
    }

    /** Same TagEditDialog, single track from a row's own "⋮" menu - [batchEditSelectedTracks]
     * is just this called with the current selection. */
    fun batchEditTracks(ids: List<TrackId>, artistName: String?, albumName: String?, year: Int?, genre: String?) {
        viewModelScope.launch { libraryRepository.batchEditTracks(ids, artistName, albumName, year, genre) }
    }

    suspend fun searchMusicBrainz(title: String, artistName: String?) =
        libraryRepository.searchMusicBrainz(title, artistName)

    private val sort = MutableStateFlow(TrackSort.DATE_ADDED)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val tracks: Flow<PagingData<Track>> =
        sort.flatMapLatest { libraryRepository.tracks(it) }.cachedIn(viewModelScope)

    val albums: Flow<PagingData<AlbumSummary>> =
        libraryRepository.albums().cachedIn(viewModelScope)

    val artists: Flow<PagingData<Artist>> =
        libraryRepository.artists().cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Live - any rename/cover/artist/track change to any album updates this without needing a
    // manual refresh call (recentAlbums() is now a Room-backed Flow, not a one-shot snapshot).
    val recentAlbums: StateFlow<List<AlbumSummary>> = libraryRepository.recentAlbums(limit = 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val featuredArtists: StateFlow<List<Artist>> = libraryRepository.featuredArtists(limit = 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nowPlaying: StateFlow<NowPlayingRow?> = playerRepository.state
        .map { state -> (state as? PlaybackState.Playing)?.let { NowPlayingRow(it.trackId, it.isPlaying) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Вкладки-обзоры считаются по снимку библиотеки (см. loadBrowseGroups), поэтому без этой
        // подписки импортированные при открытом экране треки не попадали в счётчики групп до
        // переключения вкладки туда-обратно. Пересчитываем только когда реально поменялся состав
        // или поля, по которым группируем: иначе каждый инкремент счётчика прослушиваний
        // перетряхивал бы список под рукой.
        libraryRepository.allTracksOrderedFlow()
            .distinctUntilChangedBy { list -> list.map { Triple(it.id.value, it.genre, it.path) } }
            .drop(1)
            .onEach { _uiState.value.selectedTab.takeIf { it.isBrowse }?.let { loadBrowseGroups(it, keepPrevious = true) } }
            .launchIn(viewModelScope)
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, openedGroup = null, openedGroupTracks = emptyList())
        if (tab.isBrowse) loadBrowseGroups(tab)
    }

    fun setSort(newSort: TrackSort) {
        sort.value = newSort
        _uiState.value = _uiState.value.copy(sort = newSort)
    }

    /** Группы для вкладок Жанры/Папки/Теги/Годы считаются в памяти по снимку библиотеки, а не
     * отдельными GROUP BY-запросами: снимок уже есть (allTracksOrdered используется для очереди),
     * а группировка по строке пути или по жанру в SQL всё равно потребовала бы своих запросов и
     * миграций ради экрана, который открывают редко. */
    private fun loadBrowseGroups(tab: LibraryTab, keepPrevious: Boolean = false) {
        if (!keepPrevious) _uiState.value = _uiState.value.copy(browseLoading = true, browseGroups = emptyList())
        viewModelScope.launch {
            val all = libraryRepository.allTracksOrdered()
            browseSource = all
            val groups = when (tab) {
                LibraryTab.GENRES -> all.groupBy { it.genre?.trim().orEmpty().ifBlank { UNKNOWN_KEY } }
                    .map { (key, tracks) -> BrowseGroup(key, if (key == UNKNOWN_KEY) "Без жанра" else key, tracks.size) }
                    .sortedBy { it.title.lowercase() }
                LibraryTab.FOLDERS -> all.groupBy { folderOf(it.path) }
                    .map { (key, tracks) -> BrowseGroup(key, key.substringAfterLast('/').ifBlank { key }, tracks.size) }
                    .sortedBy { it.title.lowercase() }
                LibraryTab.YEARS -> {
                    val years = libraryRepository.trackYears()
                    browseYears = years
                    all.groupBy { years[it.id]?.toString() ?: UNKNOWN_KEY }
                        .map { (key, tracks) -> BrowseGroup(key, if (key == UNKNOWN_KEY) "Без года" else key, tracks.size) }
                        .sortedByDescending { it.key }
                }
                LibraryTab.TAGS -> tagRepository.tags().first().map { tag ->
                    BrowseGroup(tag.id.value, tag.name, tagRepository.tracksForTag(tag.id).first().size)
                }
                else -> emptyList()
            }
            _uiState.value = _uiState.value.copy(browseGroups = groups, browseLoading = false)
        }
    }

    private var browseSource: List<Track> = emptyList()
    private var browseYears: Map<TrackId, Int> = emptyMap()

    fun openBrowseGroup(group: BrowseGroup) {
        val tab = _uiState.value.selectedTab
        _uiState.value = _uiState.value.copy(openedGroup = group)
        viewModelScope.launch {
            val tracks = when (tab) {
                LibraryTab.GENRES -> browseSource.filter { it.genre?.trim().orEmpty().ifBlank { UNKNOWN_KEY } == group.key }
                LibraryTab.FOLDERS -> browseSource.filter { folderOf(it.path) == group.key }
                LibraryTab.YEARS -> browseSource.filter { (browseYears[it.id]?.toString() ?: UNKNOWN_KEY) == group.key }
                LibraryTab.TAGS -> tagRepository.tracksForTag(dev.nami.domain.TagId(group.key)).first()
                else -> emptyList()
            }
            _uiState.value = _uiState.value.copy(openedGroupTracks = tracks)
        }
    }

    fun closeBrowseGroup() {
        _uiState.value = _uiState.value.copy(openedGroup = null, openedGroupTracks = emptyList())
    }

    private fun folderOf(path: String) = path.substringBeforeLast('/', "").ifBlank { "/" }

    private companion object {
        const val UNKNOWN_KEY = " none"
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerRepository.addToQueue(
                dev.nami.domain.PlayableTrack(
                    id = track.id,
                    title = track.title,
                    artistName = track.artistName,
                    path = track.path,
                    artworkPath = track.albumArtworkPath,
                    format = track.format,
                    cueStartMs = track.cueStartMs,
                    cueEndMs = track.cueEndMs,
                ),
            )
        }
    }

    fun importFiles(uris: List<String>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                importProgress = ImportProgress(0, uris.size, currentFileName = "Подготовка...", phase = "Импорт файлов")
            )
            try {
                libraryRepository.import(ImportSource.Files(uris)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial import failure: full error handling/reporting is a later task.
                // Swallow so viewModelScope survives and rebuildIndex still runs below.
            } finally {
                searchRepository.rebuildIndex()
                _uiState.value = _uiState.value.copy(importProgress = null)
            }
        }
    }

    fun importFolder(treeUri: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                importProgress = ImportProgress(0, 0, currentFileName = "Сканирование папки...", phase = "Импорт папки")
            )
            try {
                libraryRepository.import(ImportSource.Folder(treeUri)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial import failure: full error handling/reporting is a later task.
                // Swallow so viewModelScope survives and rebuildIndex still runs below.
            } finally {
                searchRepository.rebuildIndex()
                _uiState.value = _uiState.value.copy(importProgress = null)
            }
        }
    }

    /** П.md §2 "Режим наблюдения за папкой" - reruns folder import for every remembered SAF
     * tree, one at a time. No true background watch exists for SAF trees on Android, so this is
     * called on cold start and from a manual "Обновить" action instead of ever running silently
     * in the background. */
    fun rescanWatchedFolders() {
        val folders = settingsRepository.watchedFolders.value
        if (folders.isEmpty()) return
        viewModelScope.launch {
            for (treeUri in folders) {
                try {
                    libraryRepository.import(ImportSource.Folder(treeUri)).collect { progress ->
                        _uiState.value = _uiState.value.copy(importProgress = progress)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One watched folder failing (permission revoked, tree gone) shouldn't stop
                    // the rest from rescanning.
                }
            }
            searchRepository.rebuildIndex()
            _uiState.value = _uiState.value.copy(importProgress = null)
        }
    }

    fun addWatchedFolder(treeUri: String) {
        settingsRepository.addWatchedFolder(treeUri)
        importFolder(treeUri)
    }

    fun removeWatchedFolder(treeUri: String) {
        settingsRepository.removeWatchedFolder(treeUri)
    }

    val watchedFolders: StateFlow<List<String>> = settingsRepository.watchedFolders

    fun importZip(uri: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                importProgress = ImportProgress(
                    done = 0,
                    total = 0,
                    currentFileName = "Подготовка архива...",
                    phase = "Импорт из архива",
                )
            )
            var count = 0
            try {
                libraryRepository.import(ImportSource.Zip(uri)).collect { progress ->
                    _uiState.value = _uiState.value.copy(importProgress = progress)
                    if (progress.done > 0) count = progress.done
                }
                _importResult.value = if (count > 0) "Импорт завершён ($count треков)" else "В архиве не найдено подходящих аудиофайлов"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importResult.value = "Ошибка при импорте архива: ${e.message}"
            } finally {
                searchRepository.rebuildIndex()
                _uiState.value = _uiState.value.copy(importProgress = null)
            }
        }
    }

    fun deleteTrack(id: TrackId) {
        deleteTracks(setOf(id))
    }

    fun toggleTrackSelection(id: TrackId) {
        val current = _uiState.value.selectedTrackIds
        _uiState.value = _uiState.value.copy(
            selectedTrackIds = if (id in current) current - id else current + id,
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedTrackIds = emptySet())
    }

    /** Replaces the whole selection - used by drag-select and "select all". */
    fun setSelectedTracks(ids: Set<TrackId>) {
        _uiState.value = _uiState.value.copy(selectedTrackIds = ids)
    }

    fun deleteSelectedTracks() {
        val ids = _uiState.value.selectedTrackIds
        if (ids.isEmpty()) return
        deleteTracks(ids)
        clearSelection()
    }

    private fun deleteTracks(ids: Set<TrackId>) {
        viewModelScope.launch {
            libraryRepository.deleteTracks(ids.toList())
            playerRepository.removeTracks(ids)
            _uiState.value = _uiState.value.copy(lastDeletedTrackIds = ids)
        }
    }

    fun undoLastDelete() {
        val ids = _uiState.value.lastDeletedTrackIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { trashRepository.restoreTrack(it) }
            _uiState.value = _uiState.value.copy(lastDeletedTrackIds = emptySet())
        }
    }

    fun dismissDeleteSnackbar() {
        _uiState.value = _uiState.value.copy(lastDeletedTrackIds = emptySet())
    }

    fun renameTrack(id: TrackId, title: String) {
        viewModelScope.launch { libraryRepository.renameTrack(id, title) }
    }

    fun setTrackNote(id: TrackId, note: String?) {
        viewModelScope.launch { libraryRepository.setTrackNote(id, note) }
    }

    fun toggleAlbumSelection(id: AlbumId) {
        val current = _uiState.value.selectedAlbumIds
        _uiState.value = _uiState.value.copy(
            selectedAlbumIds = if (id in current) current - id else current + id,
        )
    }

    fun clearAlbumSelection() {
        _uiState.value = _uiState.value.copy(selectedAlbumIds = emptySet())
    }

    /** New empty album, then hands its id back so the caller can navigate straight to its detail
     * screen - that screen already does everything a "create album" flow needs (rename, cover,
     * single/album toggle, add/remove tracks), so there's no separate composer screen. */
    fun createAlbum(onCreated: (AlbumId) -> Unit) {
        viewModelScope.launch {
            val id = libraryRepository.createAlbum(title = "Новый альбом", artistId = null)
            onCreated(id)
        }
    }

    fun deleteSelectedAlbums() {
        val ids = _uiState.value.selectedAlbumIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { albumId ->
                val trackIds = libraryRepository.tracksInAlbum(albumId).first().map { it.id }
                libraryRepository.deleteTracks(trackIds)
            }
            clearAlbumSelection()
        }
    }
}
