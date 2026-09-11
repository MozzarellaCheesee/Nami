package dev.nami.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LoopRange
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackSourcePreference
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import dev.nami.domain.QueueOrigin
import dev.nami.domain.RepeatMode
import dev.nami.domain.SettingsRepository
import dev.nami.domain.ShuffleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

private const val SMART_RESUME_THRESHOLD_MS = 12 * 60 * 60 * 1000L
private const val SAVED_QUEUE_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val serverAudioRepository: dev.nami.domain.ServerAudioRepository,
) : PlayerRepository {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state

    private val _queue = MutableStateFlow(PlayerQueue.EMPTY)
    override val queue: StateFlow<PlayerQueue> = _queue

    private val _autoAdvanceSignal = MutableStateFlow(0)
    override val autoAdvanceSignal: StateFlow<Int> = _autoAdvanceSignal

    private val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled
    // Snapshot of the queue's MediaItems in their pre-shuffle order, taken the moment shuffle
    // turns on - what setShuffleEnabled(false) restores. Null whenever shuffle is off (nothing
    // to restore) or after play() starts a fresh context.
    private var preShuffleOrder: MutableList<MediaItem>? = null

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode

    private val _activeLoop = MutableStateFlow<LoopRange?>(null)
    override val activeLoop: StateFlow<LoopRange?> = _activeLoop

    override suspend fun setActiveLoop(loop: LoopRange?) {
        _activeLoop.value = loop
    }

    private val _playbackSource = MutableStateFlow(dev.nami.domain.TrackPlaybackSource.LOCAL)
    override val playbackSource: StateFlow<dev.nami.domain.TrackPlaybackSource> = _playbackSource

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    override val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs
    private var sleepTimerJob: Job? = null

    private var controller: MediaController? = null
    // Known limitation: keyed by mediaId, not by queue position — if the same track
    // appears twice in the queue (e.g. added manually while already present from
    // album context), both copies share one origin entry. A full fix needs
    // per-position origin tracking, deferred as a larger refactor.
    private val originByMediaId = mutableMapOf<String, QueueOrigin>()
    // MediaController reads a non-active timeline item's MediaMetadata across process IPC from
    // the session service, which doesn't reliably carry the full metadata we set (artwork in
    // particular came back null for upcoming/previous items in testing, current item only). We
    // already have the real data locally at play()/addToQueue() time, so cache it here and prefer
    // it over whatever the controller reports for that mediaId.
    private val trackInfoByMediaId = mutableMapOf<String, MediaItemInfo>()

    /** mediaId (локальный id трека) -> URL потока с сервера, если сервер подключён и трек
     * ему известен. Заполняется при построении очереди, читается в [toMediaItem]. */
    private val serverUrlByMediaId = mutableMapOf<String, String>()

    /** Один match-запрос на список треков; результат кладётся в [serverUrlByMediaId].
     * `replace=true` (новая очередь) очищает карту, `false` (добавление трека) - нет. */
    private suspend fun resolveServerUrls(tracks: List<PlayableTrack>, replace: Boolean = true) {
        if (replace) serverUrlByMediaId.clear()
        if (tracks.isEmpty()) return
        val unresolved = tracks.filter { track ->
            val serverId = track.id.value.removePrefix("server_").removePrefix("jam_").toLongOrNull()
                ?.takeIf { track.id.value.startsWith("server_") || track.id.value.startsWith("jam_") }
            if (serverId != null) {
                serverAudioRepository.serverStreamUrl(serverId)?.let { serverUrlByMediaId[track.id.value] = it }
                false
            } else {
                true
            }
        }
        if (unresolved.isEmpty() || !serverAudioRepository.isServerActive()) return
        val urls = serverAudioRepository.serverStreamUrls(
            unresolved.map { Triple(it.artistName, it.title, it.durationMs) },
        )
        unresolved.forEachIndexed { i, t -> urls.getOrNull(i)?.let { serverUrlByMediaId[t.id.value] = it } }
    }

    override suspend fun refreshCurrentSource() = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        val item = player.currentMediaItem ?: return@withContext
        val id = item.mediaId
        if (!id.startsWith("server_") && !id.startsWith("jam_")) return@withContext
        val serverId = id.substringAfter('_').toLongOrNull() ?: return@withContext
        val uri = serverAudioRepository.serverStreamUrl(serverId) ?: return@withContext
        if (!uri.startsWith("file://")) return@withContext
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val resume = player.playWhenReady
        serverUrlByMediaId[id] = uri
        player.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
        player.seekTo(index, position)
        player.prepare()
        if (resume) player.play()
        updatePlaybackSource(player)
    }
    // MediaController's onEvents only fires on discrete state changes (buffering, play/pause,
    // track change, etc.) - during steady playback that can be many seconds apart, so the
    // scrubber/position only advanced in visible jumps instead of smoothly. Player calls must
    // happen on the main thread, hence Dispatchers.Main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Counted once per track per listen, not per skip - "listened to" means past 30s or half the
    // track's length, whichever comes first (skips restarting scoring don't double-count since
    // this is keyed by mediaId, reset to null only on an actual track change).
    private var playCountedMediaId: String? = null

    // План.md §22.10 "Умное возобновление" - set the instant playback pauses (any way: the
    // toggle button, headphones unplugged, audio focus loss), cleared once acted on. Resuming
    // less than the threshold later continues from position as normal; resuming after it restarts
    // the track from 0, on the reasoning that a pause that long usually means "I moved on/forgot
    // about this", not "I'll be right back".
    private var pausedAtMs: Long? = null

    init {
        startTileNudges()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            // A crossfade hands the session to a whole new ExoPlayer (PlaybackService.promote-
            // IncomingPlayer), which reaches a controller as a playlist change, NOT as
            // MEDIA_ITEM_TRANSITION_REASON_AUTO - so the cover-slide animation below would never
            // fire for a crossfaded transition. The service bumps this extra on each handover.
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
                    if (extras.containsKey(EXTRA_CROSSFADE_HANDOVER)) _autoAdvanceSignal.value++
                }
            })
            .buildAsync()
        future.addListener(
            {
                controller = future.get()
                controller?.let { c ->
                    publishState(c)
                    publishQueue(c)
                    updatePlaybackSource(c)
                    _repeatMode.value = c.repeatMode.toDomainRepeatMode()
                }
                controller?.addListener(
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            publishState(player)
                            publishQueue(player)
                            updatePlaybackSource(player)
                            _repeatMode.value = player.repeatMode.toDomainRepeatMode()
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            val uriStr = controller?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                                _playbackSource.value = dev.nami.domain.TrackPlaybackSource.UNAVAILABLE
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (!isPlaying) {
                                val now = System.currentTimeMillis()
                                pausedAtMs = now
                                // Persisted, not just in-memory - see SettingsRepository.
                                // lastPlaybackQueueTrackIds's doc for why (a paused, backgrounded
                                // service is killable, wiping pausedAtMs along with everything
                                // else in-memory). The WHOLE queue, not just the current track --
                                // restoring only the one playing track silently dropped the rest
                                // of the queue on a cold-start restore.
                                controller?.let { player ->
                                    if (player.mediaItemCount == 0) return@let
                                    val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                                    settingsRepository.setLastPlayback(ids, player.currentMediaItemIndex, player.currentPosition, now)
                                }
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                _autoAdvanceSignal.value++
                            }
                            controller?.let(::updatePlaybackSource)
                            // An A-B range only makes sense for the track it was drawn on --
                            // carrying it into the next track would silently loop the wrong
                            // section (or one past that track's own duration).
                            _activeLoop.value = null
                        }
                    },
                )
                scope.launch { restoreLastPlaybackIfAny() }
                scope.launch {
                    // 500ms was the original interval - fine for a scrubber, but the lyrics
                    // screen's karaoke word-sweep visibly stepped/lagged behind the vocal at that
                    // rate (up to half a second of staleness). 100ms keeps the same cheap polling
                    // approach (no need for a smoothed/interpolated clock) while looking smooth.
                    while (true) {
                        delay(100)
                        controller?.takeIf { it.isPlaying }?.let(::publishState)
                    }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun publishState(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
        val trackId = mediaId?.let(::TrackId)
        val durationMs = player.duration.coerceAtLeast(0)
        val positionMs = player.currentPosition

        // A-B loop (План.md §22.2): checked on the same 100ms tick that already polls position
        // for the scrubber - no separate timer. Only re-seeks once actually past the end (not
        // continuously), so it can't fight a manual seek/drag the user is mid-gesture on.
        _activeLoop.value?.let { loop ->
            if (positionMs >= loop.endMs) player.seekTo(loop.startMs)
        }
        _state.value = toPlaybackState(
            trackId = trackId,
            positionMs = positionMs,
            durationMs = durationMs,
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
        )
        if (mediaId != null && trackId != null && mediaId != playCountedMediaId) {
            val threshold = if (durationMs > 0) minOf(30_000L, durationMs / 2) else 30_000L
            if (positionMs >= threshold) {
                playCountedMediaId = mediaId
                scope.launch {
                    libraryRepository.incrementPlayCount(trackId)
                    libraryRepository.recordPlayHistory(trackId, System.currentTimeMillis(), durationMs)
                }
                // Тот же порог "считается прослушиванием" (30с/половина трека), что и play count -
                // ListenBrainz ждёт того же самого момента, не отдельного правила.
                val token = settingsRepository.listenBrainzToken.value
                if (settingsRepository.scrobblingEnabled.value && token != null) {
                    val metadata = player.currentMediaItem?.mediaMetadata
                    val title = metadata?.title?.toString()
                    if (title != null) {
                        scope.launch(Dispatchers.IO) {
                            ListenBrainzScrobbler.submitListen(
                                token = token,
                                title = title,
                                artist = metadata.artist?.toString(),
                                album = metadata.albumTitle?.toString(),
                                listenedAtEpochSec = System.currentTimeMillis() / 1000,
                            )
                        }
                    }
                }

                val lastFmKey = settingsRepository.lastFmSessionKey.value
                if (settingsRepository.lastFmScrobblingEnabled.value && !lastFmKey.isNullOrBlank()) {
                    val metadata = player.currentMediaItem?.mediaMetadata
                    val title = metadata?.title?.toString()
                    if (title != null) {
                        scope.launch(Dispatchers.IO) {
                            LastFmScrobbler.submitScrobble(
                                sessionKey = lastFmKey,
                                title = title,
                                artist = metadata.artist?.toString(),
                                album = metadata.albumTitle?.toString(),
                                listenedAtEpochSec = System.currentTimeMillis() / 1000,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun publishQueue(player: Player) {
        val nowPlaying = player.currentMediaItem?.toMediaItemInfo()
        val currentIndex = player.currentMediaItemIndex
        val upcoming = if (currentIndex == androidx.media3.common.C.INDEX_UNSET) {
            emptyList()
        } else {
            (currentIndex + 1 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).toMediaItemInfo() }
        }
        val previous = if (currentIndex == androidx.media3.common.C.INDEX_UNSET || currentIndex <= 0) {
            null
        } else {
            player.getMediaItemAt(currentIndex - 1).toMediaItemInfo()
        }
        // Drop stale origins for items no longer in the timeline (played-through or removed).
        val liveIds = upcoming.mapTo(mutableSetOf()) { it.mediaId }
        originByMediaId.keys.retainAll(liveIds)
        _queue.value = buildPlayerQueue(nowPlaying, upcoming, originByMediaId, previous)
    }

    private fun MediaItem.toMediaItemInfo(): MediaItemInfo = trackInfoByMediaId[mediaId] ?: MediaItemInfo(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString(),
        artworkPath = mediaMetadata.artworkUri?.toString(),
        format = mediaMetadata.extras?.getString("format"),
    )

    private fun PlayableTrack.toMediaItemInfo(): MediaItemInfo {
        val serverArt = if (artworkPath.isNullOrBlank() && serverAudioRepository.isServerActive()) {
            val sId = id.value.removePrefix("server_").removePrefix("jam_").toLongOrNull()
            sId?.let { serverAudioRepository.serverArtworkUrl(it) }
        } else null
        return MediaItemInfo(
            mediaId = id.value,
            title = title,
            artist = artistName,
            artworkPath = artworkPath ?: serverArt,
            format = format,
        )
    }

    private fun updatePlaybackSource(player: Player) {
        val item = player.currentMediaItem
        if (item == null) {
            _playbackSource.value = dev.nami.domain.TrackPlaybackSource.LOCAL
            return
        }
        val uriStr = item.localConfiguration?.uri?.toString().orEmpty()
        val mediaId = item.mediaId

        val source = when {
            // Офлайн-кеш приложения
            uriStr.contains("ServerCache") || uriStr.endsWith(".audio") -> {
                dev.nami.domain.TrackPlaybackSource.CACHE
            }
            // Потоковый стриминг с сервера
            uriStr.startsWith("http://") || uriStr.startsWith("https://") -> {
                if (player.playerError != null || !serverAudioRepository.isServerActive()) {
                    dev.nami.domain.TrackPlaybackSource.UNAVAILABLE
                } else {
                    dev.nami.domain.TrackPlaybackSource.SERVER
                }
            }
            // Трек из джема или серверной библиотеки
            mediaId.startsWith("server_") || mediaId.startsWith("jam_") -> {
                if (player.playerError != null || !serverAudioRepository.isServerActive()) {
                    dev.nami.domain.TrackPlaybackSource.UNAVAILABLE
                } else {
                    dev.nami.domain.TrackPlaybackSource.SERVER
                }
            }
            // Локальный файл
            else -> {
                dev.nami.domain.TrackPlaybackSource.LOCAL
            }
        }
        _playbackSource.value = source
    }

    // Хвост группы C "CUE-поддержка" - ClippingConfiguration is ExoPlayer's own built-in answer
    // to "play just this slice of a file": position/duration it reports are already relative to
    // the clip, and reaching the clip's end fires the same MEDIA_ITEM_TRANSITION_REASON_AUTO as a
    // normal track ending - no separate polling/seek-on-boundary logic needed anywhere else.
    // Группа E "экран блокировки" - setArtworkUri needs an actual URI scheme to resolve through
    // Media3's own BitmapLoader (a DataSource-based loader, same one that reads the notification/
    // lock-screen bitmap in this process) - Uri.parse() on a bare filesystem path produces a
    // schemeless Uri that silently fails to load, which read as "the lock screen has no artwork".
    // Uri.fromFile() gives it a real file:// scheme. First attempt at this "fixed" it by reading
    // the whole file to bytes right here instead - but toMediaItem() runs once per track when
    // building/rebuilding the WHOLE queue, so that blocked play() on decoding every artwork file
    // in the queue before playback could even start. Fixing the actual Uri bug is both correct
    // and free - no eager I/O added.
    private fun PlayableTrack.toMediaItem(): MediaItem {
        val pref = settingsRepository.playbackSourcePreference.value
        val localExists = path.isNotBlank() && java.io.File(path).exists()
        val serverStreamUrl = serverUrlByMediaId[id.value]

        // При LOCAL_FIRST играем напрямую локальный файл (Bit-perfect, 0 трафика),
        // а серверный стрим используем, только если локального файла нет на устройстве.
        // При SERVER_STREAM отдаём предпочтение стриму с сервера.
        val finalUri = when (pref) {
            PlaybackSourcePreference.LOCAL_FIRST -> {
                if (localExists) path else (serverStreamUrl ?: path)
            }
            PlaybackSourcePreference.SERVER_STREAM -> {
                serverStreamUrl ?: path
            }
        }

        val serverArt = if (artworkPath.isNullOrBlank() && serverAudioRepository.isServerActive()) {
            val sId = id.value.removePrefix("server_").removePrefix("jam_").toLongOrNull()
            sId?.let { serverAudioRepository.serverArtworkUrl(it) }
        } else null
        val effectiveArtwork = artworkPath ?: serverArt

        return MediaItem.Builder()
            .setMediaId(id.value)
            .setUri(finalUri)
            .apply {
                val start = cueStartMs
                if (start != null) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(start)
                            .apply { cueEndMs?.let { setEndPositionMs(it) } }
                            .build(),
                    )
                }
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artistName)
                    .apply {
                        effectiveArtwork?.let { p ->
                            val uri = if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("file://")) {
                                android.net.Uri.parse(p)
                            } else {
                                android.net.Uri.fromFile(java.io.File(p))
                            }
                            setArtworkUri(uri)
                        }
                    }
                    .setExtras(android.os.Bundle().apply { putString("format", format) })
                    .build(),
            )
            .build()
    }

    // Cold start only - fires once, right after the controller connects, and only if the player
    // actually has nothing loaded (a live/backgrounded-but-alive service already has its own real
    // queue, restoring over that would be wrong). See SettingsRepository.lastPlaybackQueueTrackIds.
    private suspend fun restoreLastPlaybackIfAny() {
        val player = controller ?: return
        if (player.mediaItemCount != 0) return
        val queueIds = settingsRepository.lastPlaybackQueueTrackIds.value
        if (queueIds.isEmpty()) return
        val pausedAt = settingsRepository.lastPlaybackPausedAt.value
        val elapsed = System.currentTimeMillis() - pausedAt
        // ponytail: TTL 30 дней - если больше, очередь устарела
        if (elapsed > SAVED_QUEUE_TTL_MS) return
        if (elapsed >= SMART_RESUME_THRESHOLD_MS) return
        val savedIndex = settingsRepository.lastPlaybackQueueIndex.value
        // Tracks can vanish between the pause and this restore (deleted, moved) - resolve what's
        // still there and keep going, rather than aborting the whole restore over one missing
        // track. The saved index has to shift to match every track dropped before it.
        var resolvedIndex = savedIndex
        val playables = queueIds.mapIndexedNotNull { i, id ->
            val track = libraryRepository.track(TrackId(id)).first()
            if (track == null) {
                if (i < savedIndex) resolvedIndex--
                return@mapIndexedNotNull null
            }
            PlayableTrack(
                id = track.id,
                title = track.title,
                artistName = track.artistName,
                path = track.path,
                artworkPath = track.albumArtworkPath,
                format = track.format,
                durationMs = track.durationMs,
                cueStartMs = track.cueStartMs,
                cueEndMs = track.cueEndMs,
            )
        }
        if (playables.isEmpty()) return
        val startIndex = resolvedIndex.coerceIn(0, playables.lastIndex)
        playables.forEach { trackInfoByMediaId[it.id.value] = it.toMediaItemInfo() }
        resolveServerUrls(playables)
        val positionMs = settingsRepository.lastPlaybackPositionMs.value
        player.setMediaItems(playables.map { it.toMediaItem() }, startIndex, positionMs)
        player.prepare()
        // Deliberately no play() - restores paused, ready for the user's own tap to resume.
    }

    override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {
        originByMediaId.clear()
        trackInfoByMediaId.clear()
        // A fresh context starts unshuffled - there is no "pre-shuffle order" left to restore
        // from a previous queue, and leaving the flag on would silently mislabel the new queue.
        _shuffleEnabled.value = false
        preShuffleOrder = null
        tracks.forEach { trackInfoByMediaId[it.id.value] = it.toMediaItemInfo() }
        // Цепочки (П.md §20) применяются именно здесь - play() единственный вход, через который
        // очередь вообще возникает, так что одного места хватает и для плейлиста, и для альбома.
        val orderedIds = applyChains(tracks.map { it.id.value }, libraryRepository.trackChains())
        val byId = tracks.associateBy { it.id.value }
        val ordered = orderedIds.mapNotNull { byId[it] }
        // Якорь - тот же трек, что выбрал пользователь: перестановка не должна начинать
        // воспроизведение с чужой позиции.
        val anchorId = tracks.getOrNull(startIndex)?.id?.value
        val newStartIndex = ordered.indexOfFirst { it.id.value == anchorId }.coerceAtLeast(0)
        resolveServerUrls(ordered)
        ordered.forEach { trackInfoByMediaId[it.id.value] = it.toMediaItemInfo() }
        val items = ordered.map { it.toMediaItem() }
        controller?.apply {
            setMediaItems(items, newStartIndex, startMs)
            prepare()
            play()
            updatePlaybackSource(this)
        }
    }

    /** Ждёт до 3с чтобы MediaController успел подключиться к сервису (см. интерфейса doc) -
     * простой polling, не отдельная Deferred/coroutine машинерия ради редкого холодно-стартового
     * случая. Возвращает controller как только он готов, или null если так и не подключился. */
    private suspend fun awaitController(): MediaController? {
        repeat(60) {
            controller?.let { return it }
            delay(50)
        }
        return controller
    }

    override suspend fun awaitReady() {
        withContext(Dispatchers.Main) {
            val c = awaitController()
            c?.let {
                publishState(it)
                publishQueue(it)
            }
        }
    }

    override suspend fun toggle() {
        awaitController()?.apply {
            if (isPlaying) {
                pause()
            } else {
                pausedAtMs?.let { pausedAt ->
                    if (System.currentTimeMillis() - pausedAt >= SMART_RESUME_THRESHOLD_MS) seekTo(0)
                }
                pausedAtMs = null
                play()
            }
            publishState(this)
        }
    }

    override suspend fun seek(ms: Long) {
        awaitController()?.apply {
            seekTo(ms)
            publishState(this)
        }
    }

    override suspend fun skipNext() {
        // "Избегать треков, скипнутых 3+ раз" (План.md §22.13) - only counts as a skip when the
        // user moves on well before the track would've ended naturally; skipping in the last few
        // percent is just "the track is basically over", not "I don't want to hear this".
        awaitController()?.let { player ->
            val mediaId = player.currentMediaItem?.mediaId
            val duration = player.duration
            val position = player.currentPosition
            if (mediaId != null && duration > 0 && position < duration * 0.9) {
                scope.launch { libraryRepository.incrementSkipCount(TrackId(mediaId)) }
            }
            player.seekToNext()
            publishState(player)
            publishQueue(player)
        }
    }

    override suspend fun skipPrevious() {
        awaitController()?.apply {
            seekToPrevious()
            publishState(this)
            publishQueue(this)
        }
    }

    override suspend fun skipToPreviousTrack() {
        awaitController()?.let { player ->
            player.seekToPreviousMediaItem()
            publishState(player)
            publishQueue(player)
        }
    }

    override suspend fun stop() {
        originByMediaId.clear()
        trackInfoByMediaId.clear()
        _shuffleEnabled.value = false
        preShuffleOrder = null
        controller?.apply {
            stop()
            clearMediaItems()
        }
    }

    override suspend fun addToQueue(track: PlayableTrack) {
        val player = controller ?: return
        resolveServerUrls(listOf(track), replace = false)
        // No duplicates in the visible queue (current track + everything upcoming) - repeatedly
        // swiping/tapping "add to queue" on the same row used to stack a second copy right after
        // itself every time. If it's already queued somewhere ahead, MOVE that existing item to
        // right after the current one instead of adding a new copy - matches "queue this next"
        // even when it's already further down the list. Doesn't touch already-played history
        // before the current index; queueing the same track again once it's actually played
        // through is fine (starts a fresh copy).
        val startIndex = player.currentMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: 0
        val existingIndex = (startIndex until player.mediaItemCount).firstOrNull { i -> player.getMediaItemAt(i).mediaId == track.id.value }
        val insertIndex = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        if (existingIndex != null) {
            // Already the current track - "queue it right after current" is meaningless for
            // itself, leave it playing where it is.
            if (existingIndex != player.currentMediaItemIndex && existingIndex != insertIndex) {
                player.moveMediaItem(existingIndex, insertIndex)
            }
            return
        }
        originByMediaId[track.id.value] = QueueOrigin.MANUAL
        trackInfoByMediaId[track.id.value] = track.toMediaItemInfo()
        val wasEmpty = player.mediaItemCount == 0
        player.addMediaItem(insertIndex, track.toMediaItem())
        // Keep the pre-shuffle snapshot in sync - otherwise a track added WHILE shuffled would
        // silently vanish the moment shuffle is turned back off, since it never existed in the
        // order being restored.
        preShuffleOrder?.add(track.toMediaItem())
        if (wasEmpty) {
            player.prepare()
            player.play()
        }
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val player = controller ?: return
        val upcoming = _queue.value.upcoming
        if (fromIndex !in upcoming.indices || toIndex !in upcoming.indices) return
        // No origin restriction - both manually-queued and context (album/playlist) tracks can
        // be reordered; ExoPlayer's timeline doesn't care which is which, and there's no reason
        // a user can't rearrange what's coming up from an album same as anything else.
        val base = player.currentMediaItemIndex + 1
        player.moveMediaItem(base + fromIndex, base + toIndex)
    }

    override suspend fun removeQueueItem(index: Int) {
        val player = controller ?: return
        val upcoming = _queue.value.upcoming
        if (index !in upcoming.indices) return
        val base = player.currentMediaItemIndex + 1
        val mediaId = upcoming[index].track.id.value
        player.removeMediaItem(base + index)
        originByMediaId.remove(mediaId)
        preShuffleOrder?.removeAll { it.mediaId == mediaId }
    }

    override suspend fun removeTracks(ids: Set<TrackId>) {
        val player = controller ?: return
        if (player.currentMediaItemIndex == androidx.media3.common.C.INDEX_UNSET) return
        val idValues = ids.mapTo(mutableSetOf()) { it.value }
        // Only the current item and everything after it are exposed as "queue" — walk
        // descending so removing one index doesn't shift the ones still to check.
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex) {
            val mediaId = player.getMediaItemAt(i).mediaId
            if (mediaId in idValues) {
                player.removeMediaItem(i)
                originByMediaId.remove(mediaId)
            }
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        if (enabled == _shuffleEnabled.value) return
        val player = controller ?: return
        val currentItem = player.currentMediaItem ?: return
        if (enabled) {
            // Real reorder of the actual queue, not ExoPlayer's own shuffleModeEnabled/shuffle-
            // order machinery - that reorders PLAYBACK order while leaving getMediaItemAt(i)'s
            // linear index order untouched, which would desync it from how publishQueue() (and
            // everything downstream: MiniPlayer/NowPlaying's previous/upcoming) reads the queue.
            // Physically reordering the items keeps that whole pipeline correct for free.
            val snapshot = (0 until player.mediaItemCount).mapTo(mutableListOf()) { player.getMediaItemAt(it) }
            preShuffleOrder = snapshot
            val restItems = snapshot.filterNot { it.mediaId == currentItem.mediaId }
            val shuffled = when (settingsRepository.shuffleMode.value) {
                ShuffleMode.TRUE_RANDOM -> restItems.shuffled()
                ShuffleMode.WEIGHTED_BY_STALENESS -> weightedByStaleness(restItems)
            }
            val rest = applyAutoQueueRulesToMediaItems(shuffled)
            reorderTo(player, listOf(currentItem) + rest)
        } else {
            val original = preShuffleOrder ?: return
            reorderTo(player, original)
            preShuffleOrder = null
        }
        _shuffleEnabled.value = enabled
    }

    /** Weighted-random permutation (Efraimidis-Spirakis: key = U^(1/weight), sort descending)
     * biased toward tracks that haven't played in a while - weight grows with time since
     * [dev.nami.core.model.Track.lastPlayed] (never-played tracks get the max weight, same as a
     * track that hasn't played in ~30 days, so new imports surface early without dominating
     * every shuffle forever). Falls back to a flat weight (behaves like plain random) for any
     * track this couldn't look up. */
    /** Runs the already-shuffled order through QueueBuilder's applyAutoQueueRules - needs the
     * real Track per item (artistId/albumId/bpm/skipCount live there, not on MediaItem), then maps
     * the rule-adjusted Track order back to MediaItems by id. Falls back to the untouched order
     * for any item whose Track couldn't be looked up, rather than dropping it from the queue. */
    private suspend fun applyAutoQueueRulesToMediaItems(items: List<MediaItem>): List<MediaItem> {
        if (items.size < 2) return items
        val tracksById = items.associate { it.mediaId to libraryRepository.track(TrackId(it.mediaId)).first() }
        val knownTracks = items.mapNotNull { tracksById[it.mediaId] }
        if (knownTracks.size != items.size) return items
        val reordered = applyAutoQueueRules(knownTracks)
        val itemsById = items.associateBy { it.mediaId }
        return reordered.mapNotNull { itemsById[it.id.value] }
    }

    private suspend fun weightedByStaleness(items: List<MediaItem>): List<MediaItem> {
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val weighted = items.map { item ->
            val lastPlayed = runCatching { libraryRepository.track(TrackId(item.mediaId)).first()?.lastPlayed }.getOrNull()
            val staleness = if (lastPlayed == null) thirtyDaysMs else (now - lastPlayed).coerceIn(0, thirtyDaysMs)
            val weight = 1.0 + staleness.toDouble() / thirtyDaysMs // 1..2, never zero
            val key = Math.random().pow(1.0 / weight)
            item to key
        }
        return weighted.sortedByDescending { it.second }.map { it.first }
    }

    /** Rearranges the live queue to [target] order using ONLY [Player.moveMediaItem] - never
     * setMediaItems/remove+add, which replace the whole playlist (even an "unchanged" current
     * item) and made ExoPlayer briefly re-buffer/re-seek it: an audible stutter, and the scrubber
     * visibly snapping to 0 before jumping back to the real position. moveMediaItem is documented
     * as a pure Timeline-metadata operation - including for the currently playing item - so
     * this never touches decode/playback state or calls seekTo at all; position and playback
     * continue completely uninterrupted through the whole reorder. */
    private fun reorderTo(player: Player, target: List<MediaItem>) {
        val current = (0 until player.mediaItemCount).mapTo(mutableListOf()) { player.getMediaItemAt(it) }
        for (i in target.indices) {
            val targetId = target[i].mediaId
            if (i < current.size && current[i].mediaId == targetId) continue
            val j = (i until current.size).firstOrNull { current[it].mediaId == targetId } ?: continue
            player.moveMediaItem(j, i)
            current.add(i, current.removeAt(j))
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = mode.toPlayerRepeatMode()
        _repeatMode.value = mode
    }

    private fun Int.toDomainRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    override suspend fun startSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                _sleepTimerRemainingMs.value = remaining
                delay(1000)
                remaining -= 1000
            }
            _sleepTimerRemainingMs.value = null
            controller?.pause()
        }
    }

    override suspend fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
    }

    /** План.md §28, плитки быстрых настроек. С `ACTIVE_TILE` в манифесте система больше не зовёт
     * onStartListening сама, когда шторка открывается - плитку надо будить вызовом
     * requestListeningState, зато теперь она обновляется и пока шторка закрыта (раньше состояние
     * подтягивалось только на глазах у пользователя).
     *
     * Живёт здесь, а не в Application: единственное место, где эти три сигнала есть все сразу, и
     * оно уже создаётся ровно тогда, когда кто-то реально работает с плеером - подписка в
     * Application потянула бы за собой построение MediaController (и подъём PlaybackService) на
     * каждый старт процесса, включая обновление виджета.
     *
     * Про батарею: будим только на реальные события. Play/pause - смена трека или флага
     * isPlaying (позиция тикает раз в секунду и сюда не входит). Таймер сна - смена ОСТАВШЕЙСЯ
     * МИНУТЫ, а не секунды: в подзаголовке плитки всё равно минуты (см. formatRemaining), так что
     * из 60 тиков в минуту наружу уходит один.
     *
     * Имена классов строками, а не ::class.java - плитки лежат в :app, который зависит от
     * :player, а не наоборот. Тот же приём, что у PlaybackService с MainActivity. */
    private fun startTileNudges() {
        scope.launch {
            combine(state, queue) { playbackState, playerQueue ->
                (playbackState as? PlaybackState.Playing)?.isPlaying to playerQueue.nowPlaying?.id
            }
                .distinctUntilChanged()
                .collect {
                    requestTileListening("dev.nami.app.tile.PlayPauseTileService")
                    // Виджеты показывают то же самое (трек + play/pause), и им нужен ровно тот же
                    // триггер - это уже отфильтрованный поток "реальных" событий, без тиков позиции.
                    nudgeNamiWidgets(context)
                }
        }
        scope.launch {
            _sleepTimerRemainingMs
                .map { remaining -> remaining?.let { (it + 59_999L) / 60_000L } }
                .distinctUntilChanged()
                .collect { requestTileListening("dev.nami.app.tile.SleepTimerTileService") }
        }
    }

    /** runCatching: плитка может быть не добавлена пользователем вообще, а на части прошивок
     * requestListeningState для неизвестного компонента кидает вместо тихого no-op. */
    private fun requestTileListening(className: String) {
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                context,
                ComponentName(context.packageName, className),
            )
        }
    }
}
