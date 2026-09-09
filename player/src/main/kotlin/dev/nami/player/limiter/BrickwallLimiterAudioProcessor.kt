package dev.nami.player.limiter

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.asFloatOutput
import dev.nami.player.normalizedSampleCount
import dev.nami.player.readNormalized
import dev.nami.player.requireNamiDspInput
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

/** Общий брикволл-лимитер в самом конце DSP-цепочки Nami, перед квантователем
 * (DitherAudioProcessor).
 *
 * Зачем отдельно от true-peak в ReplayGain: там пик считается один раз по файлу, до всякой
 * обработки. А поднять сигнал выше 0 dBFS может ЛЮБАЯ стадия ниже - EQ с плюсовыми полосами,
 * свёртка с импульсом комнаты, кроссфид, "усиление воспроизведения", - и все вместе тем более,
 * даже если исходный файл был сведён с запасом. Без лимитера это упирается в жёсткое обрезание
 * в квантователе (coerceIn в normalizedToPcm16), то есть в треск.
 *
 * ВКЛЮЧЁН ВСЕГДА и отдельного тумблера не имеет - решение принято осознанно: это защитный пол, а
 * не эффект. Выключаемая защита от клиппинга защищает ровно до того момента, когда пользователь её
 * выключит и забудет, а услышит он не "лимитер выключен", а "плеер трещит на громких местах".
 * На сигнале, который и так не доходит до потолка, лимитер полностью прозрачен (множитель ровно
 * 1.0, ни одного изменённого бита - см. тест), поэтому цена постоянной работы нулевая. Тому, кому
 * нужен тракт совсем без вмешательства Nami, его даёт Hi-Fi: там кастомный AudioSink со всей
 * цепочкой вообще не строится.
 *
 * ponytail: без буфера предпросмотра (lookahead) - атака мгновенная, множитель применяется к тому
 * же отсчёту, который его и вызвал, поэтому потолок гарантированно не превышается и задержки
 * тракта не появляется. Цена - на резких транзиентах это ближе к мягкому клипу, чем к студийному
 * лимитеру. Апгрейд, если понадобится: линия задержки на ~1.5 мс и скользящий максимум по ней. */
class BrickwallLimiterAudioProcessor : BaseAudioProcessor() {

    private val limiter = BrickwallLimiter()
    private var configured = false
    private var inputIsFloat = false
    private var channelCount = 2
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputIsFloat = inputAudioFormat.requireNamiDspInput()
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        limiter.configure(inputAudioFormat.sampleRate)
        configured = true
        return inputAudioFormat.asFloatOutput()
    }

    override fun isActive(): Boolean = configured

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sampleCount = inputBuffer.normalizedSampleCount(inputIsFloat)
        if (sampleCount == 0) return
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        inputBuffer.readNormalized(scratch, sampleCount, inputIsFloat)

        limiter.process(scratch, sampleCount, channelCount)

        val output = replaceOutputBuffer(sampleCount * 4)
        val outFloats = output.asFloatBuffer()
        for (i in 0 until sampleCount) outFloats.put(scratch[i])

        inputBuffer.position(inputBuffer.limit())
        output.position(sampleCount * 4).flip()
    }

    override fun onFlush() {
        limiter.reset()
    }

    override fun onReset() {
        configured = false
    }
}

/** Сама математика лимитера, без media3 - чтобы её можно было проверить обычным тестом.
 * Обрабатывает массив чередующихся отсчётов на месте. */
internal class BrickwallLimiter {

    private var gain = 1f
    private var releaseCoeff = 0f

    fun configure(sampleRate: Int) {
        releaseCoeff = if (sampleRate > 0) exp(-1f / (sampleRate * RELEASE_S)) else 0f
        gain = 1f
    }

    fun reset() {
        gain = 1f
    }

    fun process(samples: FloatArray, count: Int, channelCount: Int) {
        val channels = channelCount.coerceAtLeast(1)
        var i = 0
        while (i + channels <= count) {
            // Каналы связаны одним множителем: раздельное ограничение левого и правого сдвигало бы
            // стереообраз на каждом громком пике, а это заметнее самого клиппинга.
            var peak = 0f
            for (ch in 0 until channels) {
                val a = abs(samples[i + ch])
                if (a > peak) peak = a
            }
            // Сначала шаг восстановления (возврат к единице по экспоненте - иначе после одного
            // пика громкость просела бы до конца трека), и только потом проверка потолка: если
            // проверять до восстановления, поднятый множитель применился бы к уже проверенному
            // отсчёту и потолок пробивался бы ровно на величину этого шага.
            val released = 1f + (gain - 1f) * releaseCoeff
            // Мгновенная атака: множитель считается по тому же отсчёту, что его вызвал, - потолок
            // не может быть превышен даже на один сэмпл.
            gain = if (peak * released > CEILING) CEILING / peak else released
            for (ch in 0 until channels) samples[i + ch] *= gain
            i += channels
        }
    }

    companion object {
        /** -0.1 dBFS. Не ровно 0: у самого края квантователь всё равно округляет вверх, а запас в
         * десятую долю децибела на слух неотличим. */
        const val CEILING = 0.98855f
        private const val RELEASE_S = 0.05f
    }
}
