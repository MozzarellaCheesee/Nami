package dev.nami.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlayerRepository {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state

    private var controller: MediaController? = null
    private var queue: List<TrackId> = emptyList()

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
                        }
                    },
                )
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun publishState(player: Player) {
        val index = player.currentMediaItemIndex
        val trackId = queue.getOrNull(index)
        _state.value = toPlaybackState(
            trackId = trackId,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
        )
    }

    override suspend fun play(queue: List<TrackId>, startIndex: Int, startMs: Long) {
        this.queue = queue
        val items = queue.map { MediaItem.fromUri(it.value) }
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
}
