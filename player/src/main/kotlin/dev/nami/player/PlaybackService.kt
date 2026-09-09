package dev.nami.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.OutputProfile
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SettingsRepository
import dev.nami.player.dither.DitherAudioProcessor
import dev.nami.player.eq.NamiRenderersFactory
import dev.nami.player.eq.ParametricEqAudioProcessor
import dev.nami.player.output.OutputDeviceDetector
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
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Set on the intent MainActivity is launched with from the system media notification/status-bar
 * chip, so it can open Now Playing directly instead of whatever screen the user left. */
const val EXTRA_OPEN_PLAYER = "dev.nami.player.OPEN_PLAYER"

/** Session extra bumped once per crossfade handover - see PlaybackService.promoteIncomingPlayer. */
const val EXTRA_CROSSFADE_HANDOVER = "dev.nami.player.CROSSFADE_HANDOVER"

/** The three custom AudioProcessors belonging to ONE built ExoPlayer. Grouped only so it's obvious
 * they are created and replaced together - sharing a set across two simultaneously-playing players
 * (which a crossfade creates) would have both audio threads writing the same processor state. */
private class DspChain {
    val replayGain = ReplayGainAudioProcessor()
    val eq = ParametricEqAudioProcessor()
    val dither = DitherAudioProcessor()
    val crossfeed = dev.nami.player.crossfeed.CrossfeedAudioProcessor()
    val convolution = dev.nami.player.convolution.ConvolutionAudioProcessor()
    val limiter = dev.nami.player.limiter.BrickwallLimiterAudioProcessor()
}

private const val ACTION_TOGGLE_LIKE = "dev.nami.ACTION_TOGGLE_LIKE"

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    // One chain per built player, never shared: a crossfade has two ExoPlayers (so two
    // DefaultAudioSinks, on two audio threads) live at once, and AudioProcessors are stateful --
    // BaseAudioProcessor's single output buffer, the EQ's per-channel biquad histories - so one
    // shared set would be written by both pipelines at the same time.
    private var dsp = DspChain()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var usingCustomSink = false
    private var crossfade: CrossfadeController? = null
    private var cast: CastController? = null

    /** DLNA/AirPlay/Яндекс Станция - см. RemoteCastController. Singleton, потому что тот же
     * объект читает экран выбора устройства. */
    @Inject lateinit var remoteCast: dev.nami.player.remote.RemoteCastController
    private var crossfadeHandovers = 0
    // Last ReplayGain value scanned for the current track, so a chain built mid-track (crossfade,
    // sink swap) starts at the right gain instead of 0dB until the next track change.
    private var currentTrackGainDb: Float? = null
    private lateinit var outputDeviceDetector: OutputDeviceDetector

    // Этап 6's "умный кроссфейд" - per-track result of TrackEndingAnalyzer, checked once when
    // the fade window is entered (CrossfadeController's isCrossfadeSuitable). true (apply
    // crossfade) is the default/fail-open value: unscanned yet, smart mode off, or a decode
    // failure all fall back to today's unconditional behavior rather than silently disabling
    // crossfade for every track. In-memory only, capped, same reasoning as NowPlayingViewModel's
    // waveform cache - this is a per-session convenience, not data worth a DB migration for.
    private var currentEndsWithNaturalFade = true
    private val endingFadeCache = LinkedHashMap<String, Boolean>()

    // Automatic audio-focus handling: pauses when another app starts playing audio/video
    // (transient or permanent focus loss), and resumes on its own once that app stops --
    // but only if playback was still going when focus was lost (a manual pause beforehand
    // stays paused, ExoPlayer tracks this itself).
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        // Явно гасим системную пространственную обработку (Spatializer, API 32+). media3 сам
        // конвертирует это в android.media.AudioAttributes.Builder.setSpatializationBehavior()
        // при сборке AudioTrack (DefaultAudioTrackProvider.getAudioTrackAttributesV21, media3
        // 1.5.0). На более старых устройствах поле просто игнорируется платформой. Без этого
        // система вправе сама решать, «опространствливать» ли вывод - а весь наш тракт (ReplayGain
        // /EQ/кроссфид/свёртка/дизер) посчитан именно под «чистый» стерео-сигнал без её вмешательства.
        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
        .build()

    // Per-track ReplayGain scan lives on the player instance's own listener list, re-attached to
    // whichever ExoPlayer is current after a swapPlayer() - kept as a field so it's the exact
    // same listener object both times, not a fresh one that'd be easy to double-add by accident.
    private val replayGainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scope.launch { refreshLikeButton() }
            val trackId = mediaItem?.mediaId?.let(::TrackId) ?: return
            scope.launch { updateReplayGainForCurrentTrack(trackId) }
            scope.launch { updateEndingFadeForCurrentTrack(trackId) }
            scope.launch { scanBpmKeyIfMissing(trackId) }
        }

        // "Кроссфейд при перелистывании назад не должен работать" - skipPrevious/
        // skipToPreviousTrack (and any other manual seek) land here as DISCONTINUITY_REASON_SEEK.
        // Without cancelling, a crossfade already mid-flight near the end of a track kept both
        // its outgoing (fading-out) and incoming players alive while the user jumped backward,
        // so the old track's tail kept bleeding into whatever the seek landed on. A natural
        // auto-transition (the actual crossfade handover) is a different reason and untouched.
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                crossfade?.cancel()
            }
        }
    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var playlistRepository: PlaylistRepository

    /** Группа E "системный мини-плеер" - лайк-кнопка в уведомлении/на экране блокировки, не
     * только в своём собственном MiniPlayer. Media3's MediaSession.Callback is the extension
     * point for a custom action beyond the standard play/pause/skip set. */
    private val likeCommand = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)

    // ICON_HEART_FILLED/UNFILLED (не ICON_UNDEFINED + свой setIconResId) - системный медиа-плеер
    // (шторка/блокировка на Android 13+) распознаёт и перерисовывает при тапе только эти
    // именованные константы иконок, кастомный resId он показывает один раз статично и не обновляет
    // после нажатия - от этого лайк в системном плеере "не менялся".
    private fun likeButton(liked: Boolean) = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
        .setDisplayName(if (liked) "Убрать из любимых" else "В любимые")
        .setSessionCommand(likeCommand)
        .build()

    private suspend fun refreshLikeButton() {
        val trackId = (player.currentMediaItem?.mediaId)?.takeIf { it.isNotEmpty() }?.let(::TrackId) ?: return
        val liked = playlistRepository.isTrackLiked(trackId).first()
        mediaSession.setCustomLayout(ImmutableList.of(likeButton(liked)))
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val connectionResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(likeCommand)
                        .build(),
                )
                .build()
            scope.launch { refreshLikeButton() }
            return connectionResult
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_LIKE) {
                val trackId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }?.let(::TrackId)
                if (trackId != null) {
                    scope.launch {
                        playlistRepository.toggleLike(trackId)
                        refreshLikeButton()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    /** What the EQ chain and player volume should actually be right now - either the user's own
     * manual EQ, or (when Этап 4's per-device profiles are on) the profile matching the currently
     * detected output route. Profiles fully replace the manual EQ while active rather than
     * layering on top of it - mixing "your own EQ" and "this device's EQ" would need the two to
     * somehow compose, and there's no principled way to do that (a doubled bass boost isn't what
     * either setting asked for). */
    private data class EffectiveEq(val gainsDb: List<Float>, val enabled: Boolean, val volumeLimitPercent: Int)

    private fun effectiveEq(): EffectiveEq =
        if (settingsRepository.outputProfilesEnabled.value) {
            // Сначала профиль конкретного устройства ("эти наушники"), потом - по типу маршрута
            // ("любой Bluetooth"), и только потом плоская кривая.
            val profile = settingsRepository.outputDeviceProfiles.value[outputDeviceDetector.currentKey.value]
                ?: settingsRepository.outputProfiles.value[outputDeviceDetector.current.value]
                ?: OutputProfile.IDENTITY
            EffectiveEq(profile.eqGainsDb, enabled = true, volumeLimitPercent = profile.volumeLimitPercent)
        } else {
            EffectiveEq(settingsRepository.eqBandGains.value, enabled = settingsRepository.eqEnabled.value, volumeLimitPercent = 100)
        }

    override fun onCreate() {
        super.onCreate()
        outputDeviceDetector = OutputDeviceDetector(this)
        val needsCustomSink = currentNeedsCustomSink()
        player = buildPlayer(needsCustomSink)
        player.addListener(replayGainListener)

        // Without a session activity, the system media notification/status-bar chip has nothing
        // to launch on tap. EXTRA_OPEN_PLAYER tells MainActivity to open Now Playing directly
        // instead of just the last screen the user left.
        // Can't reference MainActivity's class directly - it lives in the :app module, which
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
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()

        // Without this, Media3 falls back to its own bundled default (a generic circle-with-play
        // -triangle icon) for the small icon shown in the status bar and the media notification.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        crossfade = CrossfadeController(
            player = { player },
            settingsRepository = settingsRepository,
            scope = scope,
            startIncoming = ::buildIncomingPlayer,
            promote = ::promoteIncomingPlayer,
            retire = ::retireOutgoingPlayer,
            isCrossfadeSuitable = { !settingsRepository.smartCrossfadeEnabled.value || currentEndsWithNaturalFade },
        )
        BitPerfectUsbController(this, settingsRepository, scope)

        // Cast подменяет плеер у сессии целиком - см. CastController (там же про то, почему при
        // трансляции не работают EQ/кроссфейд/ReplayGain).
        cast = CastController(
            context = this,
            localPlayer = { player },
            trackByIdBlocking = { id -> runBlocking { libraryRepository.track(TrackId(id)).first() } },
            onActivePlayerChanged = { active -> mediaSession.player = active },
        ).also { it.start() }

        remoteCast.attach(
            localPlayer = { player },
            trackByIdBlocking = { id -> runBlocking { libraryRepository.track(TrackId(id)).first() } },
            onActivePlayerChanged = { active -> mediaSession.player = active },
        )

        // Whether ANY effect that needs the custom DSP sink is on right now (and Hi-Fi isn't
        // vetoing all of them). The sink is only ever built when actually needed - but the user
        // still shouldn't have to restart the app to feel a toggle, so instead of gating this once
        // at cold start, swapPlayer() rebuilds the live ExoPlayer instance (mediaSession.setPlayer,
        // preserving queue/position/playWhenReady) the moment this flips.
        combine(
            settingsRepository.eqEnabled,
            settingsRepository.replayGainEnabled,
            settingsRepository.ditherEnabled,
            settingsRepository.playbackGainDb,
            settingsRepository.crossfeedEnabled,
        ) { eq, replayGain, dither, boostDb, crossfeed ->
            eq || replayGain || dither || boostDb != 0f || crossfeed
        }
            .combine(settingsRepository.convolutionEnabled) { effectsOn, convolution -> effectsOn || convolution }
            .combine(settingsRepository.hiFiEnabled) { effectsOn, hiFi -> effectsOn to hiFi }
            .combine(settingsRepository.outputProfilesEnabled) { (effectsOn, hiFi), profilesEnabled ->
                !hiFi && (effectsOn || profilesEnabled)
            }
            .distinctUntilChanged()
            .onEach { needed -> if (needed != usingCustomSink) swapPlayer(needed) }
            .launchIn(scope)

        // Этап 4's parametric EQ (Beta) and per-device profiles (also Этап 4, Beta): gains apply
        // live (see ParametricEqAudioProcessor), but the on/off switch itself only takes effect on
        // DefaultAudioSink's next pipeline rebuild - force one via a same-position seek so
        // flipping a toggle (or the output route changing) is felt right away instead of "starting
        // with the next track". swapPlayer() above already gives a fresh pipeline when the sink
        // itself needed to change; this seek covers flips that don't (e.g. gains changing, or
        // toggling EQ off while ReplayGain/dither keep the sink alive).
        combine(
            settingsRepository.eqEnabled,
            settingsRepository.eqBandGains,
            settingsRepository.outputProfilesEnabled,
            settingsRepository.outputProfiles,
            outputDeviceDetector.currentKey,
        ) { _, _, _, _, _ -> effectiveEq() }
            .combine(settingsRepository.outputDeviceProfiles) { _, _ -> effectiveEq() }
            .distinctUntilChanged()
            .onEach { effective ->
                dsp.eq.setGains(effective.gainsDb)
                val wasEnabled = dsp.eq.enabled
                dsp.eq.enabled = effective.enabled
                if (effective.enabled != wasEnabled && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
                // Routed through CrossfadeController rather than writing player.volume directly --
                // that controller is the sole owner of player.volume (it runs every tick
                // regardless of whether crossfade itself is on, see its own doc), so setting a
                // ceiling here can never race its fade math the way a second independent writer
                // of the same field could.
                crossfade?.volumeCeiling = effective.volumeLimitPercent / 100f
            }
            .launchIn(scope)

        settingsRepository.ditherEnabled
            .onEach { enabled ->
                val wasEnabled = dsp.dither.enabled
                dsp.dither.enabled = enabled
                if (enabled != wasEnabled && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        // Кроссфид и свёртка: тот же приём, что у дизера выше - тумблер вступает в силу только на
        // перестройке конвейера DefaultAudioSink, поэтому подталкиваем её сиком на месте.
        settingsRepository.crossfeedEnabled
            .onEach { enabled ->
                val wasEnabled = dsp.crossfeed.enabled
                dsp.crossfeed.enabled = enabled
                if (enabled != wasEnabled && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        // Файл импульса читается тут, а не в процессоре: разбор WAV - это диск и аллокации, в
        // аудиопотоке им не место.
        combine(settingsRepository.convolutionEnabled, settingsRepository.convolutionIrPath) { enabled, path -> enabled to path }
            .onEach { (enabled, path) ->
                reloadImpulseResponse(path)
                val wasActive = dsp.convolution.isActive()
                dsp.convolution.enabled = enabled
                if (dsp.convolution.isActive() != wasActive && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        settingsRepository.replayGainEnabled
            .onEach { enabled ->
                val wasActive = dsp.replayGain.isActive()
                dsp.replayGain.enabled = enabled
                if (dsp.replayGain.isActive() != wasActive && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

        // "Усиление воспроизведения" - flat library-wide boost, live regardless of ReplayGain's
        // own toggle (see ReplayGainAudioProcessor.isActive()).
        settingsRepository.playbackGainDb
            .onEach { boostDb ->
                val wasActive = dsp.replayGain.isActive()
                dsp.replayGain.boostDb = boostDb
                if (dsp.replayGain.isActive() != wasActive && player.playbackState != Player.STATE_IDLE) {
                    player.seekTo(player.currentPosition)
                }
            }
            .launchIn(scope)

    }

    private fun currentNeedsCustomSink(): Boolean =
        !settingsRepository.hiFiEnabled.value && (
            settingsRepository.eqEnabled.value ||
                settingsRepository.replayGainEnabled.value ||
                settingsRepository.ditherEnabled.value ||
                settingsRepository.playbackGainDb.value != 0f ||
                settingsRepository.crossfeedEnabled.value ||
                settingsRepository.convolutionEnabled.value ||
                settingsRepository.outputProfilesEnabled.value
            )

    /** Fresh, correctly-seeded processors for one player. Seeding matters: a chain built mid-session
     * (crossfade, sink swap) must start at the settings the user already has, not at defaults. */
    private fun newDspChain(): DspChain = DspChain().apply {
        val effective = effectiveEq()
        eq.enabled = effective.enabled
        eq.setGains(effective.gainsDb)
        dither.enabled = settingsRepository.ditherEnabled.value
        replayGain.enabled = settingsRepository.replayGainEnabled.value
        replayGain.boostDb = settingsRepository.playbackGainDb.value
        replayGain.setGainDb(currentTrackGainDb)
        crossfeed.enabled = settingsRepository.crossfeedEnabled.value
        convolution.enabled = settingsRepository.convolutionEnabled.value
        convolution.impulseResponse = loadedImpulseResponse
    }

    /** Разобранный импульс держим на сервисе, а не в процессоре: цепочка пересоздаётся на каждом
     * кроссфейде и смене sink, а разбор WAV с ресемплингом - это десятки миллисекунд и мегабайты,
     * которые незачем повторять. Перечитывается только когда пользователь сменил файл. */
    private var loadedImpulseResponse: dev.nami.player.convolution.ImpulseResponse? = null
    private var loadedImpulsePath: String? = null

    private fun reloadImpulseResponse(path: String?) {
        if (path == loadedImpulsePath) return
        loadedImpulsePath = path
        loadedImpulseResponse = path?.let { dev.nami.player.convolution.IrWavLoader.load(java.io.File(it)) }
        dsp.convolution.impulseResponse = loadedImpulseResponse
    }

    private fun buildPlayer(useCustomSink: Boolean, handleAudioFocus: Boolean = true): ExoPlayer {
        usingCustomSink = useCustomSink
        val chain = newDspChain()
        dsp = chain
        // Local files only, no network wait - widen the buffer window so several tracks
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
        val builder = if (useCustomSink) {
            ExoPlayer.Builder(this, NamiRenderersFactory(this, chain.replayGain, chain.eq, chain.dither, chain.crossfeed, chain.convolution, chain.limiter))
        } else {
            ExoPlayer.Builder(this)
        }
        return builder
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            // ACTION_AUDIO_BECOMING_NOISY - без него отключение BT-наушников/выдёргивание
            // проводных на паузу не ставило, звук просто переключался на динамик и продолжал
            // играть вслух.
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /** The incoming half of a real crossfade (see CrossfadeController): a second ExoPlayer already
     * playing the next queue item from 0 while the current one finishes. It gets the whole queue at
     * that item's index, so promoting it later keeps previous/next and the queue screen intact.
     *
     * Audio focus is deliberately NOT handled by this one. A second focus request from the same app
     * makes the framework tell the first requester it lost focus, and ExoPlayer's AudioFocusManager
     * would then pause the track we are in the middle of fading out - the crossfade would cut
     * instead of blend. promoteIncomingPlayer() re-arms focus once it's the only player left. */
    private fun buildIncomingPlayer(): ExoPlayer? {
        val old = player
        val nextIndex = old.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return null
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        if (items.isEmpty()) return null
        val fresh = buildPlayer(usingCustomSink, handleAudioFocus = false)
        fresh.addListener(replayGainListener)
        fresh.setMediaItems(items, nextIndex, /* startPositionMs = */ 0L)
        fresh.repeatMode = old.repeatMode
        fresh.shuffleModeEnabled = old.shuffleModeEnabled
        fresh.volume = 0f
        fresh.prepare()
        fresh.playWhenReady = true
        return fresh
    }

    /** Hands the session over the moment the incoming track starts sounding, so the notification and
     * the Now Playing screen follow the audio instead of lagging a full fade behind it. The bumped
     * session extra is how PlayerRepositoryImpl tells this apart from an ordinary playlist change --
     * a player swap doesn't reach a MediaController as MEDIA_ITEM_TRANSITION_REASON_AUTO, so without
     * it the cover-slide animation for an auto-advance would silently stop happening on crossfades. */
    private fun promoteIncomingPlayer(fresh: ExoPlayer) {
        // Во время трансляции плеер сессии принадлежит CastController - подставлять туда локальный
        // значило бы молча оборвать трансляцию (кроссфейда при этом всё равно нет, локальный плеер
        // на паузе, но подстраховка дешевле разбора такого бага).
        if (cast?.isCasting != true && !remoteCast.isRemote) mediaSession.player = fresh
        player = fresh
        crossfadeHandovers++
        mediaSession.setSessionExtras(Bundle().apply { putInt(EXTRA_CROSSFADE_HANDOVER, crossfadeHandovers) })
    }

    /** Audio focus is only re-armed here, once the faded-out player is gone: a second focus request
     * while it still held focus is what the framework answers by telling IT it lost focus, and
     * ExoPlayer's AudioFocusManager would then pause the track mid-fade. */
    private fun retireOutgoingPlayer(old: ExoPlayer) {
        old.release()
        player.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
    }

    /** Swaps the live ExoPlayer for one built with (or without) the custom float-output sink,
     * carrying the queue/position/playback state across so the listener never hears a gap or a
     * restart - this is what lets EQ/ReplayGain/dither/playback-gain engage immediately instead
     * of needing an app restart, without going back to forcing float output unconditionally
     * (that's what caused the chipmunk-pitch regression). */
    private fun swapPlayer(useCustomSink: Boolean) {
        // A crossfade in flight owns a second player built against the OLD sink choice; drop it
        // rather than leave it playing (and leaking) past the swap.
        crossfade?.cancel()
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

        if (cast?.isCasting != true && !remoteCast.isRemote) mediaSession.player = fresh
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
            // Blocking decode - runs on Dispatchers.Default so it doesn't touch Main.immediate,
            // which the rest of this scope (and the player itself) lives on.
            val scanned = kotlinx.coroutines.withContext(Dispatchers.Default) { ReplayGainScanner.scan(track.path) }
            if (scanned != null) libraryRepository.setTrackReplayGain(trackId, scanned)
            scanned
        }
        currentTrackGainDb = gain
        dsp.replayGain.setGainDb(gain)
    }

    private suspend fun updateEndingFadeForCurrentTrack(trackId: TrackId) {
        // Fails open (stays true, crossfade applies as it always did) rather than doing the
        // decode work at all when the setting is off - this analysis is pure overhead unless
        // "умный кроссфейд" is actually on.
        if (!settingsRepository.smartCrossfadeEnabled.value) {
            currentEndsWithNaturalFade = true
            return
        }
        val cached = endingFadeCache[trackId.value]
        if (cached != null) {
            currentEndsWithNaturalFade = cached
            return
        }
        val track = libraryRepository.track(trackId).first() ?: return
        val naturalFade = kotlinx.coroutines.withContext(Dispatchers.Default) {
            dev.nami.player.replaygain.TrackEndingAnalyzer.endsWithNaturalFade(track.path)
        } ?: true
        if (endingFadeCache.size >= 30) endingFadeCache.remove(endingFadeCache.keys.first())
        endingFadeCache[trackId.value] = naturalFade
        currentEndsWithNaturalFade = naturalFade
    }

    /** BPM/key (План.md §3), cached on the track once scanned - never re-scanned. Runs
     * unconditionally on every play (not gated behind a setting like the other two scans) since
     * QueueBuilder's autoQueue rules (План.md §22.13) need it available for any track without a
     * separate "did you enable this" toggle - it's cheap to skip once cached, same file already
     * gets fully decoded once for ReplayGain regardless. */
    private suspend fun scanBpmKeyIfMissing(trackId: TrackId) {
        val track = libraryRepository.track(trackId).first() ?: return
        // Both fields are always written together below - requiring both present here (not
        // "either") avoids a stuck-forever gap where a partial result (say the tempo estimator
        // failed but the key one didn't) permanently skips ever retrying the field that failed.
        if (track.bpm != null && track.musicalKey != null) return
        val result = kotlinx.coroutines.withContext(Dispatchers.Default) {
            dev.nami.player.analysis.BpmKeyAnalyzer.scan(track.path)
        }
        if (result.bpm != null || result.musicalKey != null) {
            libraryRepository.setTrackBpmKey(trackId, result.bpm, result.musicalKey)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onDestroy() {
        outputDeviceDetector.release()
        crossfade?.cancel()
        cast?.release()
        remoteCast.release()
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
