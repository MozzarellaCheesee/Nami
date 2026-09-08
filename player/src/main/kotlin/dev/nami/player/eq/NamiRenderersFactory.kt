package dev.nami.player.eq

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.nami.player.convolution.ConvolutionAudioProcessor
import dev.nami.player.crossfeed.CrossfeedAudioProcessor
import dev.nami.player.dither.DitherAudioProcessor
import dev.nami.player.replaygain.ReplayGainAudioProcessor

/** Only override point DefaultRenderersFactory exposes for inserting custom AudioProcessors into
 * the real playback path - everything else (video/text/metadata renderers, codec selection)
 * stays whatever DefaultRenderersFactory already does. Chain order: ReplayGain (level-match
 * first) -> EQ (tone-shape) -> Dither (last, right before the sink hands the buffer to AudioTrack).
 *
 * Float output is deliberately OFF, and that is the whole fix for the "everything plays sped up,
 * pitched up and full of crackle" bug. Two independently fatal reasons, both verified against the
 * media3 1.5.0 bytecode this module actually links:
 *
 *  1. `DefaultAudioSink.configure()` only appends `audioProcessorChain.getAudioProcessors()` on the
 *     INT branch. On the float branch the pipeline is literally just `ToFloatPcmAudioProcessor` --
 *     these three processors would never run at all. So float output bought nothing.
 *  2. `setEnableFloatOutput(true)` makes the sink advertise ENCODING_PCM_FLOAT as directly
 *     supported, and `MediaCodecAudioRenderer.getMediaFormat()` then sets KEY_PCM_ENCODING=4 on the
 *     platform decoder. When a vendor MediaCodec accepts that key but does not echo `pcm-encoding`
 *     back in its output format, media3's `onOutputFormatChanged` falls back to ENCODING_PCM_16BIT
 *     - so a 4-byte-per-sample buffer gets read as 2-byte samples: exactly 2x the frame count,
 *     i.e. double speed, an octave up, with the float bit patterns themselves audible as noise.
 *     That is the reported symptom, precisely, and it's why it happened even when the processors
 *     were doing nothing (commit d8ba977).
 *
 * So the DSP runs on the int16 stream the sink hands us anyway (ToInt16Pcm -> channel mapping ->
 * trimming -> наши процессоры -> silence-skipping -> Sonic). Same real DSP, on the path the device
 * is actually known to play correctly.
 *
 * Это НЕ означает, что тракт 16-битный. Float-точность взята там, где она действительно что-то
 * решает - внутри самой цепочки: каждый процессор Nami принимает int16 или float и отдаёт float,
 * а замыкающий DitherAudioProcessor единственный возвращает поток в int16, подмешивая дизер прямо
 * перед округлением. Итого одно квантование на всю цепочку вместо пяти и настоящий in-quantizer
 * дизер - подробнее в Pcm16.kt. Обе проблемы выше касаются флага синка, а не арифметики, поэтому
 * они этой схеме не мешают.
 *
 * Порядок в массиве обязателен: DitherAudioProcessor идёт последним, потому что после наших
 * процессоров media3 ставит silence-skipping и Sonic, а те принимают только int16. */
@UnstableApi
class NamiRenderersFactory(
    context: Context,
    private val replayGainProcessor: ReplayGainAudioProcessor,
    private val eqProcessor: ParametricEqAudioProcessor,
    private val ditherProcessor: DitherAudioProcessor,
    private val crossfeedProcessor: CrossfeedAudioProcessor,
    private val convolutionProcessor: ConvolutionAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // Порядок: уровень -> тембр -> комната (IR) -> наушники (кроссфид) -> дизер.
            // Свёртка с IR стоит до кроссфида, потому что импульс комнаты описывает то, что
            // происходит со звуком ДО ушей слушателя, а кроссфид моделирует уже саму голову.
            // Дизер обязан быть последним: он маскирует ошибку округления всех, кто выше.
            .setAudioProcessors(
                arrayOf(
                    replayGainProcessor,
                    eqProcessor,
                    convolutionProcessor,
                    crossfeedProcessor,
                    ditherProcessor,
                ),
            )
            .build()
}
