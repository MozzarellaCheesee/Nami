package dev.nami.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaybackState

/** Set on the intent MainActivity is launched with from the system media notification/status-bar
 * chip, so it can open Now Playing directly instead of whatever screen the user left. */
const val EXTRA_OPEN_PLAYER = "dev.nami.player.OPEN_PLAYER"

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        // Local files only, no network wait -- widen the buffer window so several tracks
        // ahead/behind the current one stay decoded and ready, instead of ExoPlayer's default
        // which only keeps a small window and drops the back buffer entirely (causing a visible
        // stall on skipNext/skipPrevious).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setBackBuffer(/* backBufferDurationMs = */ 60_000, /* retainBackBufferFromKeyframe = */ true)
            .build()
        // Automatic audio-focus handling: pauses when another app starts playing audio/video
        // (transient or permanent focus loss), and resumes on its own once that app stops --
        // but only if playback was still going when focus was lost (a manual pause beforehand
        // stays paused, ExoPlayer tracks this itself).
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
        // Without a session activity, the system media notification/status-bar chip has nothing
        // to launch on tap. EXTRA_OPEN_PLAYER tells MainActivity to open Now Playing directly
        // instead of just the last screen the user left.
        // Can't reference MainActivity's class directly -- it lives in the :app module, which
        // depends on :player, not the other way around.
        val openIntent = Intent().apply {
            setClassName(packageName, "dev.nami.app.MainActivity")
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(sessionActivity).build()
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
