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
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = future.get()
                controller?.addListener(
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            publishState(player)
                            publishQueue(player)
                        }
                    },
                )
                scope.launch {
                    while (true) {
                        delay(500)
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
        controller?.apply {
            stop()
            clearMediaItems()
        }
    }

    override suspend fun addToQueue(track: PlayableTrack) {
        val player = controller ?: return
        originByMediaId[track.id.value] = QueueOrigin.MANUAL
        trackInfoByMediaId[track.id.value] = track.toMediaItemInfo()
        val wasEmpty = player.mediaItemCount == 0
        val insertIndex = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertIndex, track.toMediaItem())
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
}
