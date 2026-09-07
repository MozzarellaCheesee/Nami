package dev.nami.player

import androidx.media3.exoplayer.ExoPlayer
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Этап 4's crossfade -- NOT a true overlapping mix of two tracks (Media3's ExoPlayer decodes one
 * item at a time; a real overlap needs two players sharing one AudioTrack, out of scope for the
 * risk it'd add to the one already-proven playback path). This is the honest, lower-risk version:
 * a soft fade-out as a track ends and fade-in as the next one starts, driven purely by
 * `player.volume` -- no custom AudioSink/RenderersFactory, so unlike EQ/ReplayGain/dither it needs
 * no restart to take effect and can't destabilize decoding at all. */
class CrossfadeController(
    // Lambda, not a fixed instance -- PlaybackService can swap out the live ExoPlayer (see
    // swapPlayer()) when EQ/ReplayGain/dither toggle, and this always needs the CURRENT one.
    private val player: () -> ExoPlayer,
    private val settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val p = player()
        if (!settingsRepository.crossfadeEnabled.value) {
            if (p.volume != 1f) p.volume = 1f
            return
        }
        p.volume = volumeFor(p.currentPosition, p.duration)
    }

    companion object {
        private const val TICK_MS = 150L
        const val FADE_MS = 3000L

        /** Pure so it's testable without an ExoPlayer -- 1.0 outside the fade windows, ramping
         * near the very start (fade-in after a transition) and very end (fade-out before one) of
         * the current item. durationMs <= 0 (unknown/live) always returns full volume. */
        fun volumeFor(positionMs: Long, durationMs: Long): Float {
            if (durationMs <= 0) return 1f
            val remainingMs = durationMs - positionMs
            return when {
                positionMs < FADE_MS -> (positionMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
                remainingMs < FADE_MS -> (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
                else -> 1f
            }
        }
    }
}
