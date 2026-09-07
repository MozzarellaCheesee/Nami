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
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerQueue
import dev.nami.domain.PlayerRepository
import dev.nami.domain.QueueOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
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
    // turns on -- what setShuffleEnabled(false) restores. Null whenever shuffle is off (nothing
    // to restore) or after play() starts a fresh context.
    private var preShuffleOrder: MutableList<MediaItem>? = null

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
    // MediaController's onEvents only fires on discrete state changes (buffering, play/pause,
    // track change, etc.) -- during steady playback that can be many seconds apart, so the
    // scrubber/position only advanced in visible jumps instead of smoothly. Player calls must
    // happen on the main thread, hence Dispatchers.Main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Counted once per track per listen, not per skip -- "listened to" means past 30s or half the
    // track's length, whichever comes first (skips restarting scoring don't double-count since
    // this is keyed by mediaId, reset to null only on an actual track change).
    private var playCountedMediaId: String? = null

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            // A crossfade hands the session to a whole new ExoPlayer (PlaybackService.promote-
            // IncomingPlayer), which reaches a controller as a playlist change, NOT as
            // MEDIA_ITEM_TRANSITION_REASON_AUTO -- so the cover-slide animation below would never
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
                controller?.addListener(
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            publishState(player)
                            publishQueue(player)
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                _autoAdvanceSignal.value++
                            }
                        }
                    },
                )
                scope.launch {
                    // 500ms was the original interval -- fine for a scrubber, but the lyrics
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
                scope.launch { libraryRepository.incrementPlayCount(trackId) }
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

    private fun PlayableTrack.toMediaItemInfo(): MediaItemInfo = MediaItemInfo(
        mediaId = id.value,
        title = title,
        artist = artistName,
        artworkPath = artworkPath,
        format = format,
    )

    private fun PlayableTrack.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setUri(path)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistName)
                .apply { artworkPath?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                .setExtras(android.os.Bundle().apply { putString("format", format) })
                .build(),
        )
        .build()

    override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {
        originByMediaId.clear()
        trackInfoByMediaId.clear()
        // A fresh context starts unshuffled -- there is no "pre-shuffle order" left to restore
        // from a previous queue, and leaving the flag on would silently mislabel the new queue.
        _shuffleEnabled.value = false
        preShuffleOrder = null
        tracks.forEach { trackInfoByMediaId[it.id.value] = it.toMediaItemInfo() }
        val items = tracks.map { it.toMediaItem() }
        controller?.apply {
            setMediaItems(items, startIndex, startMs)
            prepare()
            play()
        }
    }

    override suspend fun toggle() {
        controller?.apply { if (isPlaying) pause() else play() }
    }

    override suspend fun seek(ms: Long) {
        controller?.seekTo(ms)
    }

    override suspend fun skipNext() {
        controller?.seekToNext()
    }

    override suspend fun skipPrevious() {
        controller?.seekToPrevious()
    }

    override suspend fun skipToPreviousTrack() {
        controller?.seekToPreviousMediaItem()
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
        // No duplicates in the visible queue (current track + everything upcoming) -- repeatedly
        // swiping/tapping "add to queue" on the same row used to stack a second copy right after
        // itself every time. If it's already queued somewhere ahead, MOVE that existing item to
        // right after the current one instead of adding a new copy -- matches "queue this next"
        // even when it's already further down the list. Doesn't touch already-played history
        // before the current index; queueing the same track again once it's actually played
        // through is fine (starts a fresh copy).
        val startIndex = player.currentMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: 0
        val existingIndex = (startIndex until player.mediaItemCount).firstOrNull { i -> player.getMediaItemAt(i).mediaId == track.id.value }
        val insertIndex = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        if (existingIndex != null) {
            // Already the current track -- "queue it right after current" is meaningless for
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
        // Keep the pre-shuffle snapshot in sync -- otherwise a track added WHILE shuffled would
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
        // No origin restriction -- both manually-queued and context (album/playlist) tracks can
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
            // order machinery -- that reorders PLAYBACK order while leaving getMediaItemAt(i)'s
            // linear index order untouched, which would desync it from how publishQueue() (and
            // everything downstream: MiniPlayer/NowPlaying's previous/upcoming) reads the queue.
            // Physically reordering the items keeps that whole pipeline correct for free.
            val snapshot = (0 until player.mediaItemCount).mapTo(mutableListOf()) { player.getMediaItemAt(it) }
            preShuffleOrder = snapshot
            val rest = snapshot.filterNot { it.mediaId == currentItem.mediaId }.shuffled()
            reorderTo(player, listOf(currentItem) + rest)
        } else {
            val original = preShuffleOrder ?: return
            reorderTo(player, original)
            preShuffleOrder = null
        }
        _shuffleEnabled.value = enabled
    }

    /** Rearranges the live queue to [target] order using ONLY [Player.moveMediaItem] -- never
     * setMediaItems/remove+add, which replace the whole playlist (even an "unchanged" current
     * item) and made ExoPlayer briefly re-buffer/re-seek it: an audible stutter, and the scrubber
     * visibly snapping to 0 before jumping back to the real position. moveMediaItem is documented
     * as a pure Timeline-metadata operation -- including for the currently playing item -- so
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
}
