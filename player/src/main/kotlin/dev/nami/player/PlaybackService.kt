package dev.nami.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaybackState
import dev.nami.domain.SettingsRepository
import dev.nami.player.dither.DitherAudioProcessor
import dev.nami.player.eq.NamiRenderersFactory
import dev.nami.player.eq.ParametricEqAudioProcessor
import dev.nami.player.replaygain.ReplayGainAudioProcessor
import dev.nami.player.replaygain.ReplayGainScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Set on the intent MainActivity is launched with from the system media notification/status-bar
 * chip, so it can open Now Playing directly instead of whatever screen the user left. */
const val EXTRA_OPEN_PLAYER = "dev.nami.player.OPEN_PLAYER"

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val eqProcessor = ParametricEqAudioProcessor()
    private val replayGainProcessor = ReplayGainAudioProcessor()
    private val ditherProcessor = DitherAudioProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository

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
        // Reverted from "always build the custom sink" -- on real hardware, forcing
        // setEnableFloatOutput(true) unconditionally (inside NamiRenderersFactory) made every
        // track play back sped-up and pitched-up, a known class of Media3/vendor-HAL bug with
        // float PCM output on some devices. Confirmed the instant it went live for 100% of
        // playback (not just the Beta effects users). Correctness beats convenience here: back to
        // only using the custom sink when the user has actually turned an effect on before this
        // cold start -- means EQ/ReplayGain/dither still need one app restart to engage for the
        // very first time, but ordinary playback (the vast majority of sessions) stays on the
        // exact plain, proven path.
        val needsCustomSink = settingsRepository.eqEnabled.value ||
            settingsRepository.replayGainEnabled.value ||
            settingsRepository.ditherEnabled.value ||
            settingsRepository.playbackGainDb.value != 0f
        val playerBuilder = if (needsCustomSink) {
            ExoPlayer.Builder(this, NamiRenderersFactory(this, replayGainProcessor, eqProcessor, ditherProcessor))
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
        // away instead of "starting with the next track".
        settingsRepository.eqBandGains
            .onEach { gains -> eqProcessor.setGains(gains) }
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

        settingsRepository.ditherEnabled
            .onEach { enabled ->
                val wasEnabled = ditherProcessor.enabled
                ditherProcessor.enabled = enabled
                if (enabled != wasEnabled && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        settingsRepository.replayGainEnabled
            .onEach { enabled ->
                val wasActive = replayGainProcessor.isActive()
                replayGainProcessor.enabled = enabled
                if (replayGainProcessor.isActive() != wasActive && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        // "Усиление воспроизведения" -- flat library-wide boost, live regardless of ReplayGain's
        // own toggle (see ReplayGainAudioProcessor.isActive()).
        settingsRepository.playbackGainDb
            .onEach { boostDb ->
                val wasActive = replayGainProcessor.isActive()
                replayGainProcessor.boostDb = boostDb
                if (replayGainProcessor.isActive() != wasActive && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        // ReplayGain scan happens lazily, once per track, on first play -- not during import
        // (would stall the whole folder scan on decoding every file). Cheap after the first time:
        // the result is cached on the track (see ReplayGainScanner/replayGainDb).
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val trackId = mediaItem?.mediaId?.let(::TrackId) ?: return
                scope.launch { updateReplayGainForCurrentTrack(trackId) }
            }
        })

        BitPerfectUsbController(this, player, settingsRepository, scope)
        CrossfadeController(player, settingsRepository, scope)
    }

    private suspend fun updateReplayGainForCurrentTrack(trackId: TrackId) {
        if (!settingsRepository.replayGainEnabled.value) return
        val track = libraryRepository.track(trackId).first() ?: return
        val cachedGain = track.replayGainDb
        val gain = if (cachedGain != null) {
            cachedGain
        } else {
            // Blocking decode -- runs on Dispatchers.Default so it doesn't touch Main.immediate,
            // which the rest of this scope (and the player itself) lives on.
            val scanned = kotlinx.coroutines.withContext(Dispatchers.Default) { ReplayGainScanner.scan(track.path) }
            if (scanned != null) libraryRepository.setTrackReplayGain(trackId, scanned)
            scanned
        }
        replayGainProcessor.setGainDb(gain)
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
