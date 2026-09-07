package dev.nami.player.eq

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.nami.player.dither.DitherAudioProcessor
import dev.nami.player.replaygain.ReplayGainAudioProcessor

/** Only override point DefaultRenderersFactory exposes for inserting custom AudioProcessors into
 * the real playback path -- everything else (video/text/metadata renderers, codec selection)
 * stays whatever DefaultRenderersFactory already does. Chain order: ReplayGain (level-match
 * first) -> EQ (tone-shape) -> Dither (added last, right before the float stream leaves for the
 * sink's own bit-depth conversion). */
@UnstableApi
class NamiRenderersFactory(
    context: Context,
    private val replayGainProcessor: ReplayGainAudioProcessor,
    private val eqProcessor: ParametricEqAudioProcessor,
    private val ditherProcessor: DitherAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            // Float output isn't just requested, it's required -- every processor here only
            // declares itself active for ENCODING_PCM_FLOAT.
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(replayGainProcessor, eqProcessor, ditherProcessor))
            .build()
}
