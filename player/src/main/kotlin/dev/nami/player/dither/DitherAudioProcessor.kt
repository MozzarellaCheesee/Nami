package dev.nami.player.dither

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.LSB16
import dev.nami.player.PCM16_FULL_SCALE
import dev.nami.player.normalizedToPcm16
import dev.nami.player.requireNamiDspInput
import java.nio.ByteBuffer
import kotlin.random.Random

/** Замыкающая стадия DSP-цепочки Nami: единственное место, где сигнал возвращается в int16, и
 * заодно TPDF-дизер (Этап 4).
 *
 * Раньше это был просто «дизер поверх уже 16-битного потока»: DefaultAudioSink отдавал каждому
 * процессору int16, поэтому к моменту дизера сигнал успевал округлиться на каждой стадии выше, и
 * шум лишь маскировал уже сделанную ошибку. Теперь стадии выше отдают float (см. Pcm16.kt), а
 * округление происходит ровно здесь и ровно один раз - то есть дизер подмешивается ДО
 * квантования и стал настоящим in-quantizer дизером, каким он и должен быть.
 *
 * Активность устроена так, чтобы процессор не встревал зря и при этом гарантировал int16 на
 * выходе цепочки (дальше в конвейере media3 стоят пропуск тишины и Sonic, а они принимают
 * только int16):
 *  - вход float (значит, выше работала хоть одна DSP-стадия) - активен всегда, иначе float утёк
 *    бы дальше и конвейер упал бы на несовместимом формате;
 *  - вход int16 и дизер включён - активен, добавляет шум и переквантует;
 *  - вход int16 и дизер выключен - неактивен, поток идёт мимо нетронутым. */
class DitherAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    private var configured = false
    private var inputIsFloat = false

    private val random = Random(System.nanoTime())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputIsFloat = inputAudioFormat.requireNamiDspInput()
        configured = true
        // Всегда int16: это выход всей DSP-цепочки Nami наружу, в штатную часть конвейера media3.
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT,
        )
    }

    override fun isActive(): Boolean = configured && (inputIsFloat || enabled)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val bytesPerSample = if (inputIsFloat) 4 else 2
        val sampleCount = remaining / bytesPerSample
        val outputBytes = sampleCount * 2
        val output = replaceOutputBuffer(outputBytes)
        val outShorts = output.asShortBuffer()
        val addDither = enabled

        if (inputIsFloat) {
            val inFloats = inputBuffer.asFloatBuffer()
            for (i in 0 until sampleCount) {
                val sample = inFloats.get()
                outShorts.put(if (addDither) (sample + tpdfNoise()).normalizedToPcm16() else sample.normalizedToPcm16())
            }
        } else {
            // Вход уже 16-битный - сюда попадаем только при включённом дизере (см. isActive).
            val inShorts = inputBuffer.asShortBuffer()
            for (i in 0 until sampleCount) {
                val sample = inShorts.get() / PCM16_FULL_SCALE
                outShorts.put((sample + tpdfNoise()).normalizedToPcm16())
            }
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(outputBytes).flip()
    }

    /** Сумма двух независимых равномерных величин даёт треугольное распределение - стандартная
     * конструкция TPDF-дизера. Амплитуда - один младший разряд 16-битного потока. */
    private fun tpdfNoise(): Float =
        (random.nextFloat() - 0.5f + random.nextFloat() - 0.5f) * LSB16

    override fun onReset() {
        configured = false
    }
}
