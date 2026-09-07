package dev.nami.player.eq

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Only override point DefaultRenderersFactory exposes for inserting a custom AudioProcessor
 * into the real playback path -- everything else (video/text/metadata renderers, codec
 * selection) stays whatever DefaultRenderersFactory already does. */
@UnstableApi
class NamiRenderersFactory(
    context: Context,
    private val eqProcessor: ParametricEqAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            // Float output isn't just requested, it's required -- ParametricEqAudioProcessor
            // only declares itself active for ENCODING_PCM_FLOAT (see its isActive()/onConfigure).
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(eqProcessor))
            .build()
}
