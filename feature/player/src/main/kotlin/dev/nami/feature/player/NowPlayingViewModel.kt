package dev.nami.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LoopRange
import dev.nami.domain.LoopsRepository
import dev.nami.domain.Moment
import dev.nami.domain.MomentsRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.RepeatMode
import dev.nami.domain.SavedLoop
import dev.nami.domain.SettingsRepository
import dev.nami.player.waveform.WaveformCache
import dev.nami.player.waveform.WaveformScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
    // Nullable with a default so plain-JVM unit tests (no Robolectric in this project) can keep
    // constructing this ViewModel with just the two repositories, same as before this field
    // existed - WaveformCache itself no-ops (returns null / does nothing) when context is null.
    @ApplicationContext private val context: Context? = null,
    // Also nullable/defaulted for the same reason - only used for the night-mode pill, every
    // existing test constructs this ViewModel with just the two repositories.
    private val settingsRepository: SettingsRepository? = null,
    // Same reasoning - only used for the Now Playing heart/like button.
    private val playlistRepository: PlaylistRepository? = null,
    // Same reasoning - only used for the waveform's Moments markers.
    private val momentsRepository: MomentsRepository? = null,
    // Same reasoning - only used for saved A-B loops.
    private val loopsRepository: LoopsRepository? = null,
    // Same reasoning - only used for MiniPlayer's слепое-прослушивание mask.
    private val blindListenState: BlindListenState? = null,
    // Same reasoning - только для двух пунктов меню "Ещё" (раздача трека и "слушать со мной"),
    // которые переключаются прямо в меню, без ухода на экран "Локальная сеть".
    private val localShareRepository: dev.nami.domain.LocalShareRepository? = null,
    // Same reasoning - форма волны с сервера, если он подключён (иначе локальный WaveformScanner).
    private val serverAudioRepository: dev.nami.domain.ServerAudioRepository? = null,
    // Состояние Jam (совместного прослушивания) для отображения кнопки и активного статуса в плеере.
    private val jamRepository: dev.nami.domain.JamRepository? = null,
) : ViewModel() {

    /** Состояние активной Jam-сессии для индикации в плеере, мини-плеере и меню "Ещё". */
    val jamSession: StateFlow<dev.nami.domain.JamSession?> =
        jamRepository?.session ?: MutableStateFlow(null)

    /** Раздаётся ли сейчас трек по локальной сети (/drop) - меню "Ещё" показывает это подписью
     * и цветом прямо на ячейке, вместо перехода на отдельный экран. */
    val droppingTrack: StateFlow<Boolean> =
        localShareRepository?.dropTrack?.map { it != null }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    /** Включена ли раздача "что сейчас играет" для гостей ("Слушать со мной"). */
    val listenTogetherHosting: StateFlow<Boolean> =
        localShareRepository?.listenTogetherHostEnabled ?: MutableStateFlow(false)

    /** Включает/выключает раздачу текущего трека. Сервер поднимается сам: без него раздавать
     * нечему, а спрашивать об этом пользователя отдельной кнопкой смысла нет. */
    fun toggleDropCurrentTrack() {
        val repo = localShareRepository ?: return
        if (repo.dropTrack.value != null) {
            repo.setDropTrack(null)
            return
        }
        val nowPlayingId = playerRepository.queue.value.nowPlaying?.id ?: return
        viewModelScope.launch {
            val track = libraryRepository.track(nowPlayingId).first() ?: return@launch
            repo.startServer()
            repo.setDropTrack(track)
        }
    }

    fun toggleListenTogetherHost() {
        val repo = localShareRepository ?: return
        val next = !repo.listenTogetherHostEnabled.value
        if (next) repo.startServer()
        repo.setListenTogetherHost(next)
    }

    val listenTogetherGuestState: StateFlow<dev.nami.domain.ListenTogetherGuestState?> =
        localShareRepository?.listenTogetherGuestState ?: MutableStateFlow(null)

    fun addCurrentListenTogetherTrackToLibrary(onDone: (Boolean) -> Unit) {
        val repo = localShareRepository ?: return
        viewModelScope.launch {
            val ok = repo.addCurrentListenTogetherTrackToLibrary()
            onDone(ok)
        }
    }

    val blindModeActive: StateFlow<Boolean> = blindListenState?.active ?: MutableStateFlow(false)

    private val waveformDiskCache = WaveformCache(context)

    val playbackState: StateFlow<PlaybackState> = playerRepository.state
    val queue: StateFlow<PlayerQueue> = playerRepository.queue
    val autoAdvanceSignal: StateFlow<Int> = playerRepository.autoAdvanceSignal
    val shuffleEnabled: StateFlow<Boolean> = playerRepository.shuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = playerRepository.repeatMode
    val sleepTimerRemainingMs: StateFlow<Long?> = playerRepository.sleepTimerRemainingMs
    val nightModeEnabled: StateFlow<Boolean> = settingsRepository?.nightModeEnabled
        ?: MutableStateFlow(false)

    /** Real like state for whatever's currently playing - see PlaylistRepository.isTrackLiked
     * (backed by the "Любимые треки" system playlist, not a separate favorites concept). */
    val isCurrentTrackLiked: StateFlow<Boolean> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { trackId -> playlistRepository?.isTrackLiked(trackId) ?: kotlinx.coroutines.flow.flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleLikeCurrentTrack() {
        val trackId = (playbackState.value as? PlaybackState.Playing)?.trackId ?: return
        val repo = playlistRepository ?: return
        viewModelScope.launch { repo.toggleLike(trackId) }
    }

    /** Группа E "настраиваемые жесты" - двойной тап по обложке на Now Playing раньше ничего не
     * делал, теперь запускает то, что выбрано в Настройках. */
    val doubleTapArtworkAction: StateFlow<dev.nami.domain.GestureAction> =
        settingsRepository?.doubleTapArtworkAction ?: MutableStateFlow(dev.nami.domain.GestureAction.NONE)

    /** П.md §17 "Now Playing - конструктор" (частично, см. SettingsRepository doc). */
    val nowPlayingShowTechInfo: StateFlow<Boolean> = settingsRepository?.nowPlayingShowTechInfo ?: MutableStateFlow(true)
    val nowPlayingShowShuffle: StateFlow<Boolean> = settingsRepository?.nowPlayingShowShuffle ?: MutableStateFlow(true)
    val nowPlayingShowRepeat: StateFlow<Boolean> = settingsRepository?.nowPlayingShowRepeat ?: MutableStateFlow(true)
    val nowPlayingBlockOrder: StateFlow<List<dev.nami.domain.NowPlayingBlock>> =
        settingsRepository?.nowPlayingBlockOrder ?: MutableStateFlow(dev.nami.domain.DEFAULT_NOW_PLAYING_BLOCKS)
    /** Настраиваемое меню "Ещё" - порядок/секция/цвет пунктов, см. NowPlayingMoreMenuScreen. */
    val nowPlayingMoreItems: StateFlow<List<dev.nami.domain.NowPlayingMoreConfig>> =
        settingsRepository?.nowPlayingMoreItems ?: MutableStateFlow(dev.nami.domain.DEFAULT_NOW_PLAYING_MORE_ITEMS)
    val nowPlayingCompactCover: StateFlow<Boolean> = settingsRepository?.nowPlayingCompactCover ?: MutableStateFlow(false)
    val nowPlayingLineProgress: StateFlow<Boolean> = settingsRepository?.nowPlayingLineProgress ?: MutableStateFlow(false)

    /** §13 "действия свайпов настраиваются" - свайп вбок по мини-плееру, см. MiniPlayer. */
    val miniPlayerSideSwipeAction: StateFlow<dev.nami.domain.GestureAction> =
        settingsRepository?.miniPlayerSideSwipeAction ?: MutableStateFlow(dev.nami.domain.GestureAction.SKIP_NEXT)

    private val _requestShowLyrics = MutableSharedFlow<Unit>()
    val requestShowLyrics = _requestShowLyrics.asSharedFlow()

    val longPressArtworkAction: StateFlow<dev.nami.domain.GestureAction> =
        settingsRepository?.longPressArtworkAction ?: MutableStateFlow(dev.nami.domain.GestureAction.SHOW_LYRICS)

    fun performDoubleTapAction() {
        executeGestureAction(doubleTapArtworkAction.value)
    }

    fun performLongPressAction() {
        executeGestureAction(longPressArtworkAction.value)
    }

    private fun executeGestureAction(action: dev.nami.domain.GestureAction) {
        when (action) {
            dev.nami.domain.GestureAction.NONE -> Unit
            dev.nami.domain.GestureAction.TOGGLE_LIKE -> toggleLikeCurrentTrack()
            dev.nami.domain.GestureAction.SKIP_NEXT -> viewModelScope.launch { playerRepository.skipNext() }
            dev.nami.domain.GestureAction.PREV_TRACK -> viewModelScope.launch { playerRepository.skipPrevious() }
            dev.nami.domain.GestureAction.PLAY_PAUSE -> viewModelScope.launch { playerRepository.toggle() }
            dev.nami.domain.GestureAction.SHOW_LYRICS -> viewModelScope.launch { _requestShowLyrics.emit(Unit) }
            dev.nami.domain.GestureAction.SHUFFLE -> viewModelScope.launch {
                playerRepository.setShuffleEnabled(!playerRepository.shuffleEnabled.value)
            }
        }
    }


    /** Метки моментов (План.md §22.1) for whatever's currently playing - see WaveformScrubber's
     * `moments` param, which just draws these, and NowPlayingScreen's long-press dialog, which
     * calls [addMoment]. */
    val currentTrackMoments: StateFlow<List<Moment>> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { trackId -> momentsRepository?.momentsForTrack(trackId) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMoment(positionMs: Long, label: String, colorArgb: Int, isChapter: Boolean = false) {
        val trackId = (playbackState.value as? PlaybackState.Playing)?.trackId ?: return
        val repo = momentsRepository ?: return
        viewModelScope.launch { repo.add(trackId, positionMs, label, colorArgb, isChapter) }
    }

    fun removeMoment(id: Long) {
        val repo = momentsRepository ?: return
        viewModelScope.launch { repo.remove(id) }
    }

    /** A-B loop (План.md §22.2) - [activeLoop] is the live "looping right now" state
     * (PlayerRepository enforces it on its own 100ms position tick); [currentTrackSavedLoops] are
     * named presets the user saved earlier for this track. */
    val activeLoop: StateFlow<LoopRange?> = playerRepository.activeLoop

    fun setLoopRange(startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        viewModelScope.launch { playerRepository.setActiveLoop(LoopRange(startMs, endMs)) }
    }

    fun clearLoop() {
        viewModelScope.launch { playerRepository.setActiveLoop(null) }
    }

    private val _clipExportUri = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    val clipExportUri: StateFlow<android.net.Uri?> = _clipExportUri

    private val _videoClipExportUri = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    val videoClipExportUri: StateFlow<android.net.Uri?> = _videoClipExportUri

    private val _videoExportProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    val videoExportProgress: StateFlow<Float?> = _videoExportProgress

    /** Group D "экспорт клипа (аудио)" - см. AudioClipExporter. */
    fun exportClip(startMs: Long, endMs: Long) {
        val ctx = context ?: return
        val trackId = (playerRepository.state.value as? PlaybackState.Playing)?.trackId ?: return
        viewModelScope.launch {
            val track = libraryRepository.track(trackId).first() ?: return@launch
            val uri = withContext(Dispatchers.IO) {
                val dir = java.io.File(ctx.cacheDir, "shares").apply { mkdirs() }
                val file = java.io.File(dir, "clip.wav")
                if (!dev.nami.player.AudioClipExporter.exportClip(track.path, startMs, endMs, file)) return@withContext null
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            }
            _clipExportUri.value = uri
        }
    }

    /** Экспорт видео-открытки MP4 (9:16) с обложкой, названием, динамической волной и лирикой. */
    fun exportVideoClip(startMs: Long, endMs: Long, lyricLine: String? = null) {
        val ctx = context ?: return
        val trackId = (playerRepository.state.value as? PlaybackState.Playing)?.trackId ?: return
        viewModelScope.launch {
            val track = libraryRepository.track(trackId).first() ?: return@launch
            _videoExportProgress.value = 0f
            val uri = withContext(Dispatchers.IO) {
                val dir = java.io.File(ctx.cacheDir, "shares").apply { mkdirs() }
                val file = java.io.File(dir, "clip_${System.currentTimeMillis()}.mp4")
                val ok = dev.nami.player.VideoClipExporter.exportVideo(
                    audioPath = track.path,
                    artworkPath = track.albumArtworkPath,
                    trackTitle = track.title,
                    artistName = track.artistName,
                    lyricLine = lyricLine,
                    startMs = startMs,
                    endMs = endMs,
                    outputFile = file,
                    onProgress = { p -> _videoExportProgress.value = p },
                )
                _videoExportProgress.value = null
                if (!ok) return@withContext null
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            }
            _videoClipExportUri.value = uri
        }
    }

    fun clipExportUriShown() {
        _clipExportUri.value = null
    }

    fun videoClipExportUriShown() {
        _videoClipExportUri.value = null
    }

    val currentTrackSavedLoops: StateFlow<List<SavedLoop>> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { trackId -> loopsRepository?.loopsForTrack(trackId) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveLoop(startMs: Long, endMs: Long, name: String) {
        val trackId = (playbackState.value as? PlaybackState.Playing)?.trackId ?: return
        val repo = loopsRepository ?: return
        viewModelScope.launch { repo.save(trackId, startMs, endMs, name) }
    }

    fun removeSavedLoop(id: Long) {
        val repo = loopsRepository ?: return
        viewModelScope.launch { repo.remove(id) }
    }

    /** Full Track for the "Аудиотракт"-style file details (bitrate/size/etc.) shown near the
     * format badge - QueueTrack only carries what the mini/full player needs for display, not
     * the byte-level stuff, so this looks the real Track back up by id instead of growing
     * QueueTrack for one screen's use. */
    val currentTrackDetails: StateFlow<Track?> = playerRepository.state
        .filterIsInstance<PlaybackState.Playing>()
        .map { it.trackId }
        .distinctUntilChanged()
        // libraryRepository.track() is a real Room-observed Flow (not a one-shot lookup) - this
        // re-subscribes only when the track itself changes, not on every ~100ms position tick, so
        // a background scan finishing (BpmKeyAnalyzer, ReplayGain) updates the badge live instead
        // of needing the next track selection to see it.
        .flatMapLatest { trackId -> libraryRepository.track(trackId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Real per-track waveform for the scrubber (see WaveformScanner) - a full-track decode, so
    // it's scanned lazily off the main thread. Two-tier cache: an in-memory map for instant reuse
    // within this session, backed by WaveformCache on disk so a track already scanned in a
    // PREVIOUS session doesn't flash the placeholder shape again after an app restart while it
    // re-decodes the whole file just to reproduce the same 120 numbers as last time. Null while
    // loading/on failure - WaveformScrubber falls back to its own placeholder shape.
    private val waveformCache = LinkedHashMap<String, List<Float>>()
    private val _waveform = MutableStateFlow<List<Float>?>(null)
    val waveform: StateFlow<List<Float>?> = _waveform.asStateFlow()

    init {
        currentTrackDetails
            .distinctUntilChanged { a, b -> a?.path == b?.path }
            .onEach { track -> loadWaveform(track) }
            .launchIn(viewModelScope)
    }

    private fun loadWaveform(track: Track?) {
        val path = track?.path
        if (path == null) {
            _waveform.value = null
            return
        }
        val memoryCached = waveformCache[path]
        if (memoryCached != null) {
            _waveform.value = memoryCached
            return
        }
        // Disk read is ~120 bytes - reading it synchronously (right here, before ever touching
        // _waveform) is cheap enough to not need a dispatcher hop, and it's the whole fix: the
        // old code always set _waveform.value = null first and read the disk cache inside a
        // launched coroutine, so even an already-scanned-last-session track visibly flashed the
        // placeholder for one frame (the Compose recomposition on the null) before the disk value
        // came back on the next coroutine step. Checking synchronously first means a disk hit
        // never sets null at all.
        val diskCached = waveformDiskCache.read(path)
        if (diskCached != null) {
            rememberWaveform(path, diskCached)
            return
        }
        _waveform.value = null
        viewModelScope.launch {
            // Сначала с сервера (он уже посчитал при сканировании библиотеки), при промахе -
            // локальный полный декод.
            val fromServer =
                if (track != null && serverAudioRepository?.isServerActive() == true) {
                    serverAudioRepository.serverWaveform(track.artistName, track.title, track.durationMs)
                } else null
            val bars = fromServer
                ?: withContext(Dispatchers.Default) { WaveformScanner.scan(path) }
                ?: return@launch
            rememberWaveform(path, bars)
            withContext(Dispatchers.IO) { waveformDiskCache.write(path, bars) }
        }
    }

    private fun rememberWaveform(path: String, bars: List<Float>) {
        // Cap the in-memory cache so a long listening session doesn't grow this unbounded - each
        // track's own bar list is small (120 floats), but no reason to keep every track ever
        // played this session in RAM; the disk cache already covers "seen it before".
        if (waveformCache.size >= 30) waveformCache.remove(waveformCache.keys.first())
        waveformCache[path] = bars
        if (currentTrackDetails.value?.path == path) _waveform.value = bars
    }

    private val _externalTrackChangeSignal = MutableStateFlow(0)
    /** Bumped by playTrack/playFromLibrary/playTracks - the "a track was selected from a list tap"
     * entry points - but never by skipNext/skipPrevious, so MiniPlayer can play its track-change
     * slide animation for taps without it colliding with the animation a manual swipe already
     * plays for itself. */
    val externalTrackChangeSignal: StateFlow<Int> = _externalTrackChangeSignal.asStateFlow()

    // Блок "Слушать всё вперемешку" на главном - true пока играет ИМЕННО очередь, запущенная им
    // (не любая другая перемешка). Сбрасывается в false в начале КАЖДОЙ другой функции ниже,
    // которая грузит новую очередь - вкладка/альбом/плейлист/радио, что угодно, кроме
    // skipNext/skipPrevious внутри той же очереди (они его не трогают, пользователь всё ещё
    // слушает тот же перемешанный набор).
    private val _shuffleAllActive = MutableStateFlow(false)
    val shuffleAllActive: StateFlow<Boolean> = _shuffleAllActive.asStateFlow()

    fun playTrack(trackId: TrackId) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            val track = libraryRepository.track(trackId).first() ?: return@launch
            playerRepository.play(listOf(track.toPlayableTrack(artistName = null)), startIndex = 0)
            _externalTrackChangeSignal.value++
        }
    }

    /** Хвост группы C "офлайн-радио от трека" - см. RadioBuilder для того, что на самом деле
     * считается похожестью (локальная эвристика, не рекомендатель). */
    fun startRadio(trackId: TrackId) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            val seed = libraryRepository.track(trackId).first() ?: return@launch
            val library = libraryRepository.allTracksOrdered()
            val queue = dev.nami.domain.RadioBuilder.build(seed, library)
            playerRepository.play(queue.map { it.toPlayableTrack(artistName = null) }, startIndex = 0)
            _externalTrackChangeSignal.value++
        }
    }

    /**
     * Plays [trackId] as if it were tapped from the full "all tracks" library list: the queue is
     * every track in that list, positioned at [trackId], so skipPrevious/skipNext traverse the
     * whole library exactly like tapping a track inside an album/artist/playlist already does.
     */
    fun playFromLibrary(trackId: TrackId) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            val tracks = libraryRepository.allTracksOrdered()
            val startIndex = tracks.indexOfFirst { it.id == trackId }
            if (startIndex < 0) return@launch
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName = null) }, startIndex = startIndex)
            _externalTrackChangeSignal.value++
        }
    }

    fun playTracks(tracks: List<Track>, artistName: String?, startIndex: Int) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName) }, startIndex = startIndex)
            _externalTrackChangeSignal.value++
        }
    }

    /** Как [playTracks], но сперва применяет свои настройки плейлиста (П.md §20): эквалайзер,
     * кроссфейд, перемешивание. Незаданные (null) поля не трогаются - плейлист без своих
     * настроек не должен молча перекраивать звук под себя.
     *
     * Настройки именно применяются к глобальным, а не живут "на время плейлиста": откатывать их
     * пришлось бы по событию, которого нет (очередь можно доиграть, подменить, дополнить чужими
     * треками), и пользователь получал бы необъяснимые скачки звука. Тут это осознанно
     * односторонняя операция, как если бы он переключил их сам. */
    fun playPlaylist(playlistId: dev.nami.core.model.PlaylistId, tracks: List<Track>, startIndex: Int) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            val playlist = playlistRepository?.playlist(playlistId)?.first()
            playlist?.eqGainsCsv
                ?.split(',')
                ?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { settingsRepository?.setEqBandGains(it) }
            playlist?.crossfadeEnabled?.let { settingsRepository?.setCrossfadeEnabled(it) }

            playerRepository.play(tracks.map { it.toPlayableTrack(null) }, startIndex = startIndex)
            if (playlist?.shuffleOnStart == true) playerRepository.setShuffleEnabled(true)
            _externalTrackChangeSignal.value++
        }
    }

    /** Как [playPlaylist] (применяет свои EQ/кроссфейд плейлиста), но перемешивание всегда
     * включено - кнопка "Перемешать" рядом с Play должна тасовать независимо от того, что
     * сохранено в playlist.shuffleOnStart (та настройка про обычный запуск, не про эту кнопку). */
    fun playPlaylistShuffled(playlistId: dev.nami.core.model.PlaylistId, tracks: List<Track>) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            val playlist = playlistRepository?.playlist(playlistId)?.first()
            playlist?.eqGainsCsv
                ?.split(',')
                ?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { settingsRepository?.setEqBandGains(it) }
            playlist?.crossfadeEnabled?.let { settingsRepository?.setCrossfadeEnabled(it) }

            playerRepository.play(tracks.map { it.toPlayableTrack(null) }, startIndex = 0)
            playerRepository.setShuffleEnabled(true)
            _externalTrackChangeSignal.value++
        }
    }

    /** "Перемешать" from Album/Artist - starts the given tracks as a fresh queue, then
     * immediately shuffles it (see [PlayerRepository.setShuffleEnabled]) rather than shuffling
     * [tracks] here and starting at index 0: going through the real toggle means the player's own
     * shuffle button can un-shuffle back to this exact starting order afterwards, same as if the
     * user had tapped Play and then shuffle by hand. */
    fun playTracksShuffled(tracks: List<Track>, artistName: String?) {
        _shuffleAllActive.value = false
        viewModelScope.launch {
            playerRepository.play(tracks.map { it.toPlayableTrack(artistName) }, startIndex = 0)
            playerRepository.setShuffleEnabled(true)
            _externalTrackChangeSignal.value++
        }
    }

    /** Кнопка "Слушать всё вперемешку" на главном - специально своя функция, не [playTracksShuffled]:
     * та начинает с индекса 0 переданного списка И ТОЛЬКО ПОТОМ включает перемешку (осознанно для
     * альбома/артиста - индекс 0 там значит первый трек альбома). Для всей библиотеки список
     * приходит в порядке "как хранится" (свежедобавленные первыми) - тот же приём давал баг:
     * играть всегда начинало с самого последнего скачанного трека, потому что именно он был
     * нулевым, а перемешка применялась только к ОСТАВШЕЙСЯ части очереди. Тут список тасуется
     * заранее, поэтому и первый трек по-настоящему случайный. */
    fun playAllShuffled(tracks: List<Track>) {
        _shuffleAllActive.value = true
        viewModelScope.launch {
            val shuffled = tracks.shuffled()
            playerRepository.play(shuffled.map { it.toPlayableTrack(artistName = null) }, startIndex = 0)
            playerRepository.setShuffleEnabled(true)
            _externalTrackChangeSignal.value++
        }
    }

    fun toggleShuffle() {
        viewModelScope.launch { playerRepository.setShuffleEnabled(!playerRepository.shuffleEnabled.value) }
    }

    /** OFF -> ALL -> ONE -> OFF, the standard three-state cycle every music player's repeat
     * button uses. */
    fun cycleRepeatMode() {
        val next = when (playerRepository.repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        viewModelScope.launch { playerRepository.setRepeatMode(next) }
    }

    fun toggleNightMode() {
        val repo = settingsRepository ?: return
        repo.setNightModeEnabled(!repo.nightModeEnabled.value)
    }

    fun startSleepTimer(durationMs: Long) {
        viewModelScope.launch { playerRepository.startSleepTimer(durationMs) }
    }

    fun cancelSleepTimer() {
        viewModelScope.launch { playerRepository.cancelSleepTimer() }
    }

    fun addToQueue(track: Track, artistName: String?) {
        viewModelScope.launch { playerRepository.addToQueue(track.toPlayableTrack(artistName)) }
    }

    fun toggle() {
        viewModelScope.launch { playerRepository.toggle() }
    }

    fun seek(ms: Long) {
        viewModelScope.launch { playerRepository.seek(ms) }
    }

    fun stop() {
        viewModelScope.launch { playerRepository.stop() }
    }

    fun skipNext() {
        viewModelScope.launch { playerRepository.skipNext() }
    }

    fun skipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    /** For swipe gestures - see [PlayerRepository.skipToPreviousTrack]. */
    fun skipToPreviousTrack() {
        viewModelScope.launch { playerRepository.skipToPreviousTrack() }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { playerRepository.moveQueueItem(fromIndex, toIndex) }
    }

    fun removeQueueItem(index: Int) {
        viewModelScope.launch { playerRepository.removeQueueItem(index) }
    }

    private fun Track.toPlayableTrack(artistName: String?): PlayableTrack =
        PlayableTrack(
            id = id,
            title = title,
            artistName = artistName ?: this.artistName,
            path = path,
            artworkPath = albumArtworkPath,
            format = format,
            durationMs = durationMs,
            cueStartMs = cueStartMs,
            cueEndMs = cueEndMs,
        )
}
