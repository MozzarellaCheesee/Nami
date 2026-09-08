package dev.nami.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** Этап 4's crossfade - a REAL overlap: for the last FADE_MS of a track a second ExoPlayer is
 * already playing the next queue item from its own position 0, the two volumes cross on an
 * equal-power curve, and the outgoing player is released once it has faded to silence.
 *
 * Why it was rewritten: the previous version faded the single player's `volume` down over a track's
 * last 3 seconds and back up over the next track's first 3 seconds. That is verifiably applied (the
 * volume really does ramp 1.0 -> 0.0 -> 1.0 on the device), but it is not a crossfade at all - the
 * two ramps are sequential, so it removes music instead of overlapping it, and both windows sit
 * exactly where most tracks are already fading out / silently leading in. Result: nothing audible,
 * reported as "кроссфейд не работает" several times over. One ExoPlayer decodes one item at a time,
 * so two players is the only way to actually overlap them.
 *
 * The session is handed to the incoming player at the START of the fade, not the end: the new track
 * is the one you are hearing come up, so that's when the UI/notification should be showing it. (An
 * end-of-fade handover left the cover and title stuck on the finished track for three full seconds.)
 * The outgoing player just keeps decoding in the background, muted-and-falling, until it's released.
 *
 * The overlap is confined to the fade itself: outside it there is still exactly one player, and
 * with the setting off (the default) nothing here touches playback at all. */
class CrossfadeController(
    // Lambda, not a fixed instance - PlaybackService swaps out the live ExoPlayer (see swapPlayer()
    // and the handover below), and this always needs the CURRENT one.
    private val player: () -> ExoPlayer,
    private val settingsRepository: SettingsRepository,
    scope: CoroutineScope,
    /** Builds a second player already prepared and playing the next queue item from 0 at volume 0,
     * or null when there is nothing to cross into (last track). */
    private val startIncoming: () -> ExoPlayer?,
    /** Makes that player the media session's player, so the UI follows the track now coming up. */
    private val promote: (ExoPlayer) -> Unit,
    /** Releases the faded-out player and gives audio focus back to the surviving one. */
    private val retire: (ExoPlayer) -> Unit,
    /** Этап 6's "умный кроссфейд" gate - true means it's fine to start the overlap for the
     * CURRENT track (checked once, right as the fade window is entered). Defaults to always-true
     * so callers that don't care about this (existing tests) see identical behavior to before. */
    private val isCrossfadeSuitable: () -> Boolean = { true },
) {
    private var outgoing: ExoPlayer? = null

    /** Hard ceiling (0..1) multiplied into every volume value this controller writes - this is
     * the single owner of `player.volume` while crossfade exists (it runs every tick regardless
     * of whether the crossfade setting is on), so per-device volume-limit profiles set this
     * instead of writing player.volume directly. That's the fix for the ceiling racing an
     * in-flight fade: there's no longer a second, independent writer of the same field. */
    @Volatile var volumeCeiling: Float = 1f

    init {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                // A single bad tick (e.g. the player instance mid-swapPlayer()) must not kill
                // this loop for the rest of the session - without a catch here, any exception
                // propagates out of the while loop and the whole coroutine just quietly stops,
                // silently disabling crossfade for good with nothing to restart it.
                try {
                    tick()
                } catch (e: Exception) {
                    // Next tick tries again in TICK_MS.
                }
            }
        }
    }

    /** Ends an in-flight crossfade immediately, releasing the outgoing player - needed when the DSP
     * sink swaps mid-fade (PlaybackService.swapPlayer), when the setting is turned off, and on
     * service teardown, so a second ExoPlayer can never outlive the fade that created it. */
    fun cancel() {
        outgoing?.let(retire)
        outgoing = null
        try {
            player().volume = volumeCeiling
        } catch (e: Exception) {
            // Player already released / not built yet - nothing to restore.
        }
    }

    private fun tick() {
        val current = player()
        if (!settingsRepository.crossfadeEnabled.value) {
            if (outgoing != null) cancel()
            if (current.volume != volumeCeiling) current.volume = volumeCeiling
            return
        }

        val durationMs = current.duration
        // Progress through the overlap, measured on the INCOMING track's own position - it starts
        // at 0 by construction, so this stays exact even if it spent a moment buffering first.
        val progress = (current.currentPosition.toFloat() / FADE_MS).coerceIn(0f, 1f)

        outgoing?.let { old ->
            old.volume = fadeOut(progress) * volumeCeiling
            if (progress >= 1f) {
                outgoing = null
                retire(old)
            }
        }

        if (outgoing == null && durationMs > 0 && current.isPlaying &&
            durationMs - current.currentPosition <= FADE_MS &&
            current.repeatMode != Player.REPEAT_MODE_ONE && current.hasNextMediaItem() &&
            isCrossfadeSuitable()
        ) {
            val incoming = startIncoming()
            if (incoming != null) {
                // Otherwise the outgoing player runs on into the very item the incoming one is
                // already playing, and the same track decodes twice at once.
                current.setPauseAtEndOfMediaItems(true)
                outgoing = current
                promote(incoming)
                return
            }
        }

        // Also covers the incoming player's own fade-in (its position is inside the first FADE_MS),
        // which is the exact complement of the fadeOut() applied to the outgoing one above.
        // The tail half of volumeFor() (this track's own ending) is skipped when the smart gate
        // rejected an overlap and there IS a next item to reach - otherwise this single-player
        // ramp would quietly fade out exactly the "abrupt, don't fade" ending isCrossfadeSuitable()
        // was checking for in the first place, defeating the whole point of the gate.
        val skipTailFade = outgoing == null && current.hasNextMediaItem() &&
            current.repeatMode != Player.REPEAT_MODE_ONE && !isCrossfadeSuitable() &&
            durationMs - current.currentPosition <= FADE_MS
        current.volume = if (skipTailFade) volumeCeiling else volumeFor(current.currentPosition, durationMs) * volumeCeiling
    }

    companion object {
        private const val TICK_MS = 150L
        const val FADE_MS = 5000L
        private val HALF_PI = (PI / 2).toFloat()

        /** Equal-power pair: fadeIn(t)² + fadeOut(t)² == 1, so the summed power of the two
         * overlapping tracks stays constant across the fade. A linear pair dips ~3dB in the middle,
         * which is exactly the "sagging" hole DAWs default to equal-power crossfades to avoid. */
        fun fadeIn(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * HALF_PI)

        fun fadeOut(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * HALF_PI)

        /** The single-player ramp: the incoming half of a crossfade, and the whole effect when
         * there is nothing to cross into (last track in the queue, or repeat-one). Pure so it's
         * testable without an ExoPlayer. durationMs <= 0 (unknown/live) always returns full volume. */
        fun volumeFor(positionMs: Long, durationMs: Long): Float {
            if (durationMs <= 0) return 1f
            val remainingMs = durationMs - positionMs
            val progress = when {
                positionMs < FADE_MS -> positionMs.toFloat() / FADE_MS
                remainingMs < FADE_MS -> remainingMs.toFloat() / FADE_MS
                else -> return 1f
            }
            return fadeIn(progress)
        }
    }
}
