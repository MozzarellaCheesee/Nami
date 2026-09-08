package dev.nami.player.crossfeed

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.toPcm16
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp

/** П.md §9 "кроссфид для наушников" - кроссфид в духе Bauer/bs2b.
 *
 * Зачем он вообще: в наушниках левый канал попадает только в левое ухо, чего в живой акустике не
 * бывает - там до дальнего уха звук тоже доходит, просто позже (обход головы, ~0.3 мс) и глуше
 * (голова экранирует высокие). Стереозапись, сведённая на колонках, в наушниках из-за этого
 * "разваливается" на два источника по краям головы. Кроссфид подмешивает в каждый канал
 * противоположный - задержанный и завёрнутый по НЧ - и сцена собирается обратно к центру.
 *
 * Реализация ровно эта, без таблиц bs2b: линия задержки на [DELAY_SECONDS] плюс однополюсный НЧ на
 * [LOWPASS_HZ] на подмешиваемый сигнал. Однополюсный, а не biquad, сознательно: у настоящей
 * головы спад тоже пологий, а точную HRTF всё равно не воспроизвести без индивидуальных
 * измерений - лишний порядок фильтра дал бы точность, которой в исходных данных нет.
 *
 * Работает только на стерео: на моно кроссфид - тождественная операция (подмешивать нечего), на
 * 5.1 понятие "противоположный канал" не определено, и молча трогать многоканальный поток хуже,
 * чем не трогать вовсе. В обоих случаях [isActive] отдаёт false и процессор выпадает из цепочки.
 *
 * Считает в Float (короткие единицы, ±32768 - как EQ и ReplayGain, см. Pcm16.kt), в int16
 * округляет один раз на выходе.
 */
class CrossfeedAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false

    private var sampleRateHz = 0
    private var stereo = false

    // Кольцевой буфер на два канала. Задержка меньше миллисекунды, так что это единицы десятков
    // сэмплов - отдельная структура ради такого не нужна, хватает двух FloatArray.
    private var delayL = FloatArray(0)
    private var delayR = FloatArray(0)
    private var delayPos = 0
    private var delaySamples = 0

    // Состояние однополюсных НЧ-фильтров (по одному на подмешиваемый канал).
    private var lpL = 0f
    private var lpR = 0f
    private var lpCoeff = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        stereo = inputAudioFormat.channelCount == 2
        // Минимум один сэмпл: на 8 кГц округление вниз дало бы 0 и линия задержки выродилась бы
        // в простое подмешивание без сдвига по времени, то есть в обычное сужение стерео.
        delaySamples = (DELAY_SECONDS * sampleRateHz).toInt().coerceAtLeast(1)
        delayL = FloatArray(delaySamples)
        delayR = FloatArray(delaySamples)
        delayPos = 0
        // Однополюсный НЧ: y += k * (x - y), k из частоты среза. exp-форма, а не 1/(1+RC) - она
        // остаётся корректной и когда частота среза близка к частоте дискретизации.
        lpCoeff = 1f - exp(-2.0 * PI * LOWPASS_HZ / sampleRateHz).toFloat()
        lpL = 0f
        lpR = 0f
        return inputAudioFormat
    }

    override fun isActive(): Boolean = enabled && stereo && sampleRateHz > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.asShortBuffer()
        val outShorts = output.asShortBuffer()

        while (inShorts.remaining() >= 2) {
            val left = inShorts.get().toFloat()
            val right = inShorts.get().toFloat()

            // Самый старый сэмпл в кольце - он же задержанный на delaySamples кадров.
            val delayedL = delayL[delayPos]
            val delayedR = delayR[delayPos]
            delayL[delayPos] = left
            delayR[delayPos] = right
            delayPos = (delayPos + 1) % delaySamples

            // Фильтруем именно задержанную копию, а не текущий сэмпл: подмешиваем в ухо то, что
            // дошло до него в обход головы, а прямой сигнал должен остаться нетронутым.
            lpL += lpCoeff * (delayedL - lpL)
            lpR += lpCoeff * (delayedR - lpR)

            // NORMALIZE держит сумму в тех же рамках, что и вход: без него кроссфид на
            // коррелированном материале (а центр в стерео всегда коррелирован) поднимал бы
            // громкость на +FEED и упирался в клиппинг ровно на тех треках, что и так громкие.
            outShorts.put(((left + FEED * lpR) * NORMALIZE).toPcm16())
            outShorts.put(((right + FEED * lpL) * NORMALIZE).toPcm16())
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(remaining).flip()
    }

    override fun onFlush() {
        // Хвост от предыдущей позиции после seek - это звук из другого места трека; оставить его
        // означало бы отчётливый щелчок в первых миллисекундах после перемотки.
        delayL.fill(0f)
        delayR.fill(0f)
        delayPos = 0
        lpL = 0f
        lpR = 0f
    }

    override fun onReset() {
        sampleRateHz = 0
        stereo = false
        delayL = FloatArray(0)
        delayR = FloatArray(0)
        delaySamples = 0
    }

    companion object {
        /** ~0.3 мс - обход головы звуком, классическое значение Bauer. */
        const val DELAY_SECONDS = 0.0003f

        /** Выше этой частоты голова экранирует дальнее ухо всерьёз, ниже - почти не мешает. */
        const val LOWPASS_HZ = 700.0

        /** Уровень подмешивания (~-6 дБ). Умеренный: цель - собрать сцену, а не схлопнуть стерео. */
        const val FEED = 0.5f

        /** Компенсация роста громкости от подмешивания. */
        const val NORMALIZE = 1f / (1f + FEED)
    }
}
