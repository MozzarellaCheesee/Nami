package dev.nami.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaybackState

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onDestroy() {
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }
}

fun toPlaybackState(
    trackId: TrackId?,
    positionMs: Long,
    durationMs: Long,
    playbackState: Int,
    playWhenReady: Boolean,
): PlaybackState =
    if (trackId == null || playbackState == Player.STATE_IDLE) {
        PlaybackState.Idle
    } else {
        PlaybackState.Playing(
            trackId = trackId,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = playWhenReady && playbackState == Player.STATE_READY,
        )
    }
