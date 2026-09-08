package dev.nami.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.domain.HomeBlockConfig
import dev.nami.domain.HomeBlockType
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListeningSummary
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.Tag
import dev.nami.domain.TagId
import dev.nami.domain.TagRepository
import dev.nami.domain.TopTrackStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val BLOCK_ITEM_LIMIT = 10
private const val PLAYLIST_BLOCK_LIMIT = 6
private const val QUICK_TAGS_LIMIT = 8
private const val FORGOTTEN_MIN_AGE_MS = 14L * 24 * 60 * 60 * 1000 // П.md не даёт точное число - 2 недели ощущается как "давно"
private const val NEW_IMPORT_WINDOW_MS = 24L * 60 * 60 * 1000 // "есть новое" = импорт за последние сутки

data class HomeState(
    val blocks: List<HomeBlockConfig> = emptyList(),
    val continueListening: Track? = null,
    val recentlyAdded: List<Track> = emptyList(),
    val topWeek: List<TopTrackStat> = emptyList(),
    val randomAlbum: AlbumSummary? = null,
    val forgotten: List<Track> = emptyList(),
    val statsToday: ListeningSummary? = null,
    val quickTags: List<Tag> = emptyList(),
    val selectedTag: TagId? = null,
    val tagTracks: List<Track> = emptyList(),
    val bookmarkedPlaylists: List<PlaylistSummary> = emptyList(),
    val newImportCount: Int = 0,
)

/** П.md §14 "Главный экран - конструктор" - см. HomeBlock.kt для списка блоков и того, чего в
 * нём пока нет. Все запросы - существующие методы LibraryRepository, посчитанные один раз при
 * входе на экран (не Flow) - для дашборда, который открывают, а не держат на экране постоянно,
 * живой пересчёт на каждое изменение библиотеки не оправдан. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val tagRepository: TagRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    val blocks: StateFlow<List<HomeBlockConfig>> = settingsRepository.homeBlocks

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val enabledTypes = settingsRepository.homeBlocks.value.filter { it.enabled }.map { it.type }.toSet()
            val allTracks = if (
                HomeBlockType.RECENTLY_ADDED in enabledTypes ||
                HomeBlockType.FORGOTTEN in enabledTypes ||
                HomeBlockType.NEW_IMPORT in enabledTypes
            ) {
                libraryRepository.allTracksOrdered()
            } else {
                emptyList()
            }
            val continueListeningId = playerRepository.queue.value.nowPlaying?.id
            _state.value = HomeState(
                blocks = settingsRepository.homeBlocks.value,
                continueListening = continueListeningId?.let { libraryRepository.track(it).first() },
                recentlyAdded = if (HomeBlockType.RECENTLY_ADDED in enabledTypes) allTracks.sortedByDescending { it.dateAdded }.take(BLOCK_ITEM_LIMIT) else emptyList(),
                topWeek = if (HomeBlockType.TOP_WEEK in enabledTypes) libraryRepository.topTracks(days = 7, limit = BLOCK_ITEM_LIMIT) else emptyList(),
                randomAlbum = if (HomeBlockType.RANDOM_ALBUM in enabledTypes) libraryRepository.recentAlbums(50).first().randomOrNull() else null,
                forgotten = if (HomeBlockType.FORGOTTEN in enabledTypes) {
                    val cutoff = System.currentTimeMillis() - FORGOTTEN_MIN_AGE_MS
                    allTracks.filter { (it.lastPlayed ?: Long.MAX_VALUE) < cutoff }.sortedBy { it.lastPlayed }.take(BLOCK_ITEM_LIMIT)
                } else {
                    emptyList()
                },
                statsToday = if (HomeBlockType.STATS_TODAY in enabledTypes) libraryRepository.listeningSummary(days = 1) else null,
                quickTags = if (HomeBlockType.QUICK_TAGS in enabledTypes) tagRepository.tags().first().take(QUICK_TAGS_LIMIT) else emptyList(),
                bookmarkedPlaylists = if (HomeBlockType.BOOKMARKED_PLAYLISTS in enabledTypes) playlistRepository.recentPlaylists(PLAYLIST_BLOCK_LIMIT) else emptyList(),
                newImportCount = if (HomeBlockType.NEW_IMPORT in enabledTypes) {
                    val since = System.currentTimeMillis() - NEW_IMPORT_WINDOW_MS
                    allTracks.count { it.dateAdded >= since }
                } else {
                    0
                },
            )
        }
    }

    /** Своего экрана "треки тега" в приложении нет, поэтому блок быстрых тегов раскрывает список
     * прямо под чипами - повторный тап по тому же тегу сворачивает его обратно. */
    fun selectTag(tagId: TagId) {
        viewModelScope.launch {
            if (_state.value.selectedTag == tagId) {
                _state.value = _state.value.copy(selectedTag = null, tagTracks = emptyList())
            } else {
                val tracks = tagRepository.tracksForTag(tagId).first().take(BLOCK_ITEM_LIMIT)
                _state.value = _state.value.copy(selectedTag = tagId, tagTracks = tracks)
            }
        }
    }

    fun setHomeBlocks(blocks: List<HomeBlockConfig>) {
        settingsRepository.setHomeBlocks(blocks)
        load()
    }
}
