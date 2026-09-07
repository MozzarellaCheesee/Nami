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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private var usingCustomSink = false

    // Per-track ReplayGain scan lives on the player instance's own listener list, re-attached to
    // whichever ExoPlayer is current after a swapPlayer() -- kept as a field so it's the exact
    // same listener object both times, not a fresh one that'd be easy to double-add by accident.
    private val replayGainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val trackId = mediaItem?.mediaId?.let(::TrackId) ?: return
            scope.launch { updateReplayGainForCurrentTrack(trackId) }
        }
    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository

    override fun onCreate() {
        super.onCreate()
        val needsCustomSink = currentNeedsCustomSink()
        player = buildPlayer(needsCustomSink)
        player.addListener(replayGainListener)

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

        // Whether ANY effect that needs the custom float-output sink is on right now. Forcing
        // float output unconditionally caused sped-up/pitched-up playback on real hardware (a
        // Media3/vendor-HAL bug class), so the sink is only ever built when actually needed --
        // but the user still shouldn't have to restart the app to feel a toggle, so instead of
        // gating this once at cold start, swapPlayer() rebuilds the live ExoPlayer instance
        // (mediaSession.setPlayer, preserving queue/position/playWhenReady) the moment this flips.
        combine(
            settingsRepository.eqEnabled,
            settingsRepository.replayGainEnabled,
            settingsRepository.ditherEnabled,
            settingsRepository.playbackGainDb,
        ) { eq, replayGain, dither, boostDb -> eq || replayGain || dither || boostDb != 0f }
            .distinctUntilChanged()
            .onEach { needed -> if (needed != usingCustomSink) swapPlayer(needed) }
            .launchIn(scope)

        // Этап 4's parametric EQ (Beta): gains apply live (see ParametricEqAudioProcessor), but
        // the on/off switch itself only takes effect on DefaultAudioSink's next pipeline rebuild
        // -- force one via a same-position seek so flipping the Settings toggle is felt right
        // away instead of "starting with the next track". swapPlayer() above already gives a
        // fresh pipeline when the sink itself needed to change; this seek covers flips that don't
        // (e.g. gains changing, or toggling EQ off while ReplayGain/dither keep the sink alive).
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

        BitPerfectUsbController(this, player, settingsRepository, scope)
        CrossfadeController({ player }, settingsRepository, scope)
    }

    private fun currentNeedsCustomSink(): Boolean =
        settingsRepository.eqEnabled.value ||
            settingsRepository.replayGainEnabled.value ||
            settingsRepository.ditherEnabled.value ||
            settingsRepository.playbackGainDb.value != 0f

    private fun buildPlayer(useCustomSink: Boolean): ExoPlayer {
        usingCustomSink = useCustomSink
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
        val builder = if (useCustomSink) {
            ExoPlayer.Builder(this, NamiRenderersFactory(this, replayGainProcessor, eqProcessor, ditherProcessor))
        } else {
            ExoPlayer.Builder(this)
        }
        return builder
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
    }

    /** Swaps the live ExoPlayer for one built with (or without) the custom float-output sink,
     * carrying the queue/position/playback state across so the listener never hears a gap or a
     * restart -- this is what lets EQ/ReplayGain/dither/playback-gain engage immediately instead
     * of needing an app restart, without going back to forcing float output unconditionally
     * (that's what caused the chipmunk-pitch regression). */
    private fun swapPlayer(useCustomSink: Boolean) {
        val old = player
        val mediaItems = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val currentIndex = old.currentMediaItemIndex
        val currentPosition = old.currentPosition
        val wasPlaying = old.playWhenReady
        val repeatMode = old.repeatMode
        val shuffleModeEnabled = old.shuffleModeEnabled

        val fresh = buildPlayer(useCustomSink)
        fresh.addListener(replayGainListener)
        if (mediaItems.isNotEmpty()) {
            fresh.setMediaItems(mediaItems, currentIndex, currentPosition)
            fresh.repeatMode = repeatMode
            fresh.shuffleModeEnabled = shuffleModeEnabled
            fresh.prepare()
            fresh.playWhenReady = wasPlaying
        }

        mediaSession.player = fresh
        player = fresh
        old.release()
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
