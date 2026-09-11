package dev.nami.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.PlaylistSummary
import dev.nami.core.model.Track
import dev.nami.domain.HomeBlockConfig
import dev.nami.domain.HomeBlockType
import dev.nami.domain.DiscoveredDevice
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ListenTogetherGuestState
import dev.nami.domain.ListeningSummary
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.Tag
import dev.nami.domain.TagId
import dev.nami.domain.TagRepository
import dev.nami.domain.TopTrackStat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** false до конца первой загрузки. Без этого флага пустой стартовый HomeState неотличим от
     * "все блоки выключены", и при каждом заходе на главную сначала мелькала подсказка про
     * конструктор, а потом её сменял настоящий экран. */
    val loaded: Boolean = false,
)

/** П.md §14 "Главный экран - конструктор" - см. HomeBlock.kt для списка блоков и того, чего в
 * нём пока нет.
 *
 * Экран живой: его держат открытым во время импорта и во время прослушивания, поэтому блоки
 * пересчитываются сами, без перезахода.
 * - "Продолжить слушать" сидит прямо на playerRepository.queue -> libraryRepository.track(id),
 *   оба уже Flow, так что смена играющего трека видна сразу.
 * - Остальные блоки пересчитываются по одному триггеру: набор блоков (settingsRepository.
 *   homeBlocks) x живой список треков (allTracksOrderedFlow, Room переотдаёт его при любом
 *   изменении библиотеки - импорт, удаление, счётчик прослушиваний). Разовые suspend-запросы
 *   (топ недели, статистика, теги, плейлисты) считаются в том же пересчёте: свои Flow заводить
 *   ради них не стали - они всё равно меняются вместе с библиотекой, а на пустой библиотеке
 *   триггер не срабатывает вхолостую. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val tagRepository: TagRepository,
    private val playlistRepository: PlaylistRepository,
    private val localShareRepository: LocalShareRepository,
    private val jamRepository: dev.nami.domain.JamRepository,
) : ViewModel() {
    val blocks: StateFlow<List<HomeBlockConfig>> = settingsRepository.homeBlocks

    val jamSession: StateFlow<dev.nami.domain.JamSession?> = jamRepository.session
    val discoveredJamRooms: StateFlow<List<dev.nami.domain.DiscoveredJamRoom>> = jamRepository.discoveredRooms

    fun joinJamRoom(code: String, hostUrl: String? = null) = jamRepository.joinRoom(code, hostUrl)
    fun startJamDiscovery() = jamRepository.startDiscovery()
    fun stopJamDiscovery() = jamRepository.stopDiscovery()

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    // Блок "Рядом" (группа G) - живой, не разовый снимок как остальные восемь: список найденных
    // устройств меняется по мере того, как NSD находит соседей. Поиск запускается/гасится не тут,
    // а из DisposableEffect в HomeScreen, привязанным к тому, реально ли блок сейчас на экране -
    // ViewModel во вкладках этого приложения переживает уход с главного экрана (save/restoreState
    // у нижней панели), поэтому от его жизненного цикла сеть не выключить, а от композиции блока
    // можно. startDiscovery/stopDiscovery в самом репозитории уже идемпотентны (проверяют текущее
    // состояние), так что дублирующиеся вызовы при рекомпозиции безвредны.
    val nearbyDevices: StateFlow<List<DiscoveredDevice>> = localShareRepository.discoveredDevices
    val nearbyGuestState: StateFlow<ListenTogetherGuestState?> = localShareRepository.listenTogetherGuestState

    // Блок SHUFFLE_ALL ("Слушать всё") - тот же живой список, что уже гоняет rebuild() ниже, не
    // отдельный запрос: кнопка должна тасовать актуальную библиотеку, а не снимок на момент входа.
    val allTracks: StateFlow<List<Track>> = libraryRepository.allTracksOrderedFlow()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        // Один общий триггер пересчёта: изменился набор блоков или библиотека.
        combine(settingsRepository.homeBlocks, libraryRepository.allTracksOrderedFlow()) { blocks, tracks ->
            blocks to tracks
        }
            .onEach { (blocks, tracks) -> rebuild(blocks, tracks) }
            .launchIn(viewModelScope)

        // "Продолжить слушать" - отдельной подпиской, чтобы смена трека не тянула за собой
        // пересчёт всех остальных блоков (и наоборот).
        playerRepository.queue
            .map { it.nowPlaying?.id }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else libraryRepository.track(id) }
            .onEach { track -> _state.update { it.copy(continueListening = track) } }
            .launchIn(viewModelScope)
    }

    fun startNearbyDiscovery() {
        localShareRepository.startServer()
        localShareRepository.startDiscovery()
    }

    fun stopNearbyDiscovery() = localShareRepository.stopDiscovery()

    fun joinListenTogether(device: DiscoveredDevice) = localShareRepository.joinListenTogether(device)
    fun leaveListenTogether() = localShareRepository.leaveListenTogether()
    fun pullDrop(device: DiscoveredDevice) {
        viewModelScope.launch { localShareRepository.pullDrop(device) }
    }
    fun addCurrentListenTogetherTrackToLibrary(onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = localShareRepository.addCurrentListenTogetherTrackToLibrary()
            onDone?.invoke(ok)
        }
    }

    private suspend fun rebuild(blocks: List<HomeBlockConfig>, allTracks: List<Track>) {
        val enabledTypes = blocks.filter { it.enabled }.map { it.type }.toSet()
        val current = _state.value
        _state.value = current.copy(
            loaded = true,
            blocks = blocks,
            recentlyAdded = if (HomeBlockType.RECENTLY_ADDED in enabledTypes) allTracks.sortedByDescending { it.dateAdded }.take(BLOCK_ITEM_LIMIT) else emptyList(),
            topWeek = if (HomeBlockType.TOP_WEEK in enabledTypes) libraryRepository.topTracks(days = 7, limit = BLOCK_ITEM_LIMIT) else emptyList(),
            // Альбом выбирается один раз за жизнь экрана: перевыбирать его на каждое изменение
            // библиотеки (а её меняет даже счётчик прослушиваний) значило бы подменять карточку
            // под рукой у пользователя.
            randomAlbum = if (HomeBlockType.RANDOM_ALBUM in enabledTypes) {
                current.randomAlbum ?: libraryRepository.recentAlbums(50).first().randomOrNull()
            } else {
                null
            },
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

    /** Пересчёт отдельно звать не надо: settingsRepository.homeBlocks - StateFlow, и он же триггер. */
    fun setHomeBlocks(blocks: List<HomeBlockConfig>) = settingsRepository.setHomeBlocks(blocks)
}
