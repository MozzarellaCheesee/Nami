package dev.nami.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaybackState
import dev.nami.domain.SettingsRepository
import dev.nami.player.eq.NamiRenderersFactory
import dev.nami.player.eq.ParametricEqAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/** Set on the intent MainActivity is launched with from the system media notification/status-bar
 * chip, so it can open Now Playing directly instead of whatever screen the user left. */
const val EXTRA_OPEN_PLAYER = "dev.nami.player.OPEN_PLAYER"

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val eqProcessor = ParametricEqAudioProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Inject lateinit var settingsRepository: SettingsRepository

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
        // The custom RenderersFactory (needed to splice ParametricEqAudioProcessor into
        // DefaultAudioSink) is itself unverified on real hardware -- only actually used once the
        // user has explicitly turned EQ on themselves at least once before this cold start.
        // Default (EQ off) keeps the exact plain ExoPlayer.Builder(this) path that was already
        // working, so nothing about ordinary playback changes for anyone who hasn't opted in.
        // Known limitation: turning EQ on for the first time needs an app restart to actually
        // engage (this decision is made once, here, not re-checked per track) -- an acceptable
        // cost for keeping every other playback session on the already-proven path.
        val playerBuilder = if (settingsRepository.eqEnabled.value) {
            ExoPlayer.Builder(this, NamiRenderersFactory(this, eqProcessor))
        } else {
            ExoPlayer.Builder(this)
        }
        player = playerBuilder
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

        // Without this, Media3 falls back to its own bundled default (a generic circle-with-play
        // -triangle icon) for the small icon shown in the status bar and the media notification.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        // Этап 4's parametric EQ (Beta): gains apply live (see ParametricEqAudioProcessor), but
        // the on/off switch itself only takes effect on DefaultAudioSink's next pipeline rebuild
        // -- force one via a same-position seek so flipping the Settings toggle is felt right
        // away instead of "starting with the next track". Only meaningful when this session's
        // player was actually built with NamiRenderersFactory (eqEnabled.value was already true
        // at onCreate, see playerBuilder above) -- otherwise there's no EQ processor in the
        // pipeline for these to activate at all, and the seek is a harmless no-op.
        combine(settingsRepository.eqBassDb, settingsRepository.eqMidDb, settingsRepository.eqTrebleDb) { b, m, t -> Triple(b, m, t) }
            .onEach { (b, m, t) -> eqProcessor.setGains(b, m, t) }
            .launchIn(scope)
        settingsRepository.eqEnabled
            .onEach { enabled ->
                val wasEnabled = eqProcessor.enabled
                eqProcessor.enabled = enabled
                if (enabled != wasEnabled && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        BitPerfectUsbController(this, player, settingsRepository, scope)
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
