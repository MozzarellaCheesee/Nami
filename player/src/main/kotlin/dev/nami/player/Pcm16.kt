package dev.nami.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Общий float-тракт для DSP-процессоров Nami (ReplayGain/EQ/свёртка/кроссфид/квантователь).
 *
 * Что здесь важно понимать про формат. Все процессоры Nami принимают int16 ИЛИ float и всегда
 * отдают [C.ENCODING_PCM_FLOAT], а последним в цепочке стоит квантователь
 * (dev.nami.player.dither.DitherAudioProcessor), который единственный возвращает поток в int16.
 * Смысл ровно один: между стадиями не должно быть округления до 16 бит. Раньше каждая стадия
 * писала int16, и на цепочке ReplayGain -> EQ -> свёртка -> кроссфид -> дизер сигнал округлялся
 * пять раз подряд; теперь округление одно, в самом конце, и именно туда подмешивается дизер -
 * то есть он стал настоящим in-quantizer дизером, а не шумом поверх уже округлённого сигнала.
 *
 * Почему float-путь самого DefaultAudioSink при этом НЕ включается (setEnableFloatOutput остаётся
 * false) - см. NamiRenderersFactory: на float-ветке media3 1.5.0 наши процессоры не запускаются
 * вообще, и это ещё до того, как всплывает баг с ускоренным воспроизведением.
 *
 * Значения в float нормализованы к ±1.0 - это соглашение платформы для ENCODING_PCM_FLOAT.
 * Линейным фильтрам масштаб безразличен, а вот амплитуде дизера - нет: один LSB 16-битного
 * потока это [LSB16], а не 1.0. */

/** Полная шкала int16. Отрицательный предел на единицу больше положительного, поэтому нормируем
 * на 32768: так ±1.0 отображается ровно в шкалу, и +1.0 упирается в клип на 32767, а не заворачивается. */
internal const val PCM16_FULL_SCALE = 32768f

/** Один младший разряд 16-битного потока в нормализованных единицах. */
internal const val LSB16 = 1f / PCM16_FULL_SCALE

/** Нормализованный float -> int16. Округляет (не отбрасывает) и ограничивает: отбрасывание дало
 * бы полразряда постоянного смещения, а без ограничения громкий сэмпл после усиления завернулся
 * бы в противоположную полярность - на слух это не «громко», а треск. */
internal fun Float.normalizedToPcm16(): Short =
    (this * PCM16_FULL_SCALE).roundToInt().coerceIn(-32768, 32767).toShort()

/** Оставлено для процессоров, которые считают в «коротких единицах» (±32768). */
internal fun Float.toPcm16(): Short = roundToInt().coerceIn(-32768, 32767).toShort()

/** Единственная разрешённая точка входа по формату для DSP-процессоров Nami: принимаем int16 или
 * float, отдаём всегда float. Возвращает true, если вход был float. */
internal fun AudioProcessor.AudioFormat.requireNamiDspInput(): Boolean = when (encoding) {
    C.ENCODING_PCM_FLOAT -> true
    C.ENCODING_PCM_16BIT -> false
    else -> throw AudioProcessor.UnhandledAudioFormatException(this)
}

/** Выходной формат DSP-стадии: тот же поток, но всегда во float. */
internal fun AudioProcessor.AudioFormat.asFloatOutput(): AudioProcessor.AudioFormat =
    AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_FLOAT)

/** Раскладывает входной буфер в нормализованный float. [into] должен вмещать [count] значений. */
internal fun ByteBuffer.readNormalized(into: FloatArray, count: Int, inputIsFloat: Boolean) {
    if (inputIsFloat) {
        val floats = asFloatBuffer()
        for (i in 0 until count) into[i] = floats.get()
    } else {
        val shorts = asShortBuffer()
        for (i in 0 until count) into[i] = shorts.get() / PCM16_FULL_SCALE
    }
}

/** Сколько отсчётов (не кадров) лежит в буфере при данном формате входа. */
internal fun ByteBuffer.normalizedSampleCount(inputIsFloat: Boolean): Int =
    remaining() / (if (inputIsFloat) 4 else 2)
