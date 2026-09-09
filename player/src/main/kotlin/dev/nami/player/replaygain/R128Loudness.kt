package dev.nami.player.replaygain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Настоящее измерение громкости по ITU-R BS.1770-4 / EBU R128 (вместо прежнего простого RMS):
 * K-взвешивание, стробирование (gating) и true-peak с 4-кратной передискретизацией.
 *
 * Чистый Kotlin без Android-зависимостей - именно поэтому лежит отдельно от [ReplayGainScanner]:
 * так алгоритм проверяется обычным JVM-тестом на эталонных сигналах EBU Tech 3341, а не только
 * "скомпилировалось".
 *
 * Порядок работы: [feed] кормится блоками чередующихся (interleaved) отсчётов, нормализованных к
 * ±1.0, затем [integratedLufs] и [truePeakDbfs] дают результат.
 *
 * Ограничения, принятые осознанно:
 *  - раскладка каналов считается стандартной стерео/моно, вес G=1.0 у всех каналов. Каналы
 *    объёмного звука (Ls/Rs с весом 1.41) не различаются - у плеера локальных файлов на выходе
 *    практически всегда 1-2 канала, а угадывать раскладку по одному только числу каналов
 *    (5.1 против квадро) нечестно.
 *  - momentary/short-term (400мс/3с окна для индикаторов) не считаются: ReplayGain нужна только
 *    интегральная громкость. */
class R128Loudness(private val sampleRate: Int, private val channelCount: Int) {

    private val pre = Array(channelCount) { BiquadState() }
    private val rlb = Array(channelCount) { BiquadState() }
    private val preCoeffs = preFilterCoefficients(sampleRate)
    private val rlbCoeffs = rlbFilterCoefficients(sampleRate)

    /** Блок 400 мс с перекрытием 75% - шаг 100 мс, как требует BS.1770-4. */
    private val stepFrames = (sampleRate / 10).coerceAtLeast(1)
    private val blockFrames = stepFrames * 4

    /** Сумма квадратов K-взвешенного сигнала за каждый из четырёх последних шагов. Кольцо: блок
     * (400 мс) - это сумма четырёх подряд идущих шагов, так перекрытие 75% считается без
     * хранения самих отсчётов. */
    private val stepSums = DoubleArray(4)
    private var stepIndex = 0
    private var framesInStep = 0
    private var stepsSeen = 0

    /** Громкость каждого готового блока в LKFS - хранится целиком, потому что относительный порог
     * (второй проход стробирования) известен только после того, как измерен весь трек. */
    private val blockLoudness = ArrayList<Double>()

    private val interpolator = TruePeakInterpolator(channelCount)
    private var truePeak = 0f

    /** [samples] - чередующиеся отсчёты, нормализованные к ±1.0. [count] - сколько значений в
     * массиве реально заполнено. */
    fun feed(samples: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val frameEnd = i + channelCount
            if (frameEnd > count) break
            for (ch in 0 until channelCount) {
                val x = samples[i + ch]
                val absX = abs(x)
                if (absX > truePeak) truePeak = absX
                val filtered = rlb[ch].process(pre[ch].process(x.toDouble(), preCoeffs), rlbCoeffs)
                stepSums[stepIndex] += filtered * filtered
            }
            interpolator.feed(samples, i)?.let { if (it > truePeak) truePeak = it }
            framesInStep++
            if (framesInStep == stepFrames) closeStep()
            i = frameEnd
        }
    }

    private fun closeStep() {
        stepsSeen++
        if (stepsSeen >= 4) {
            // z_ij - средний квадрат по блоку на канал; каналы суммируются с весом G=1.
            var sum = 0.0
            for (s in stepSums) sum += s
            val meanSquare = sum / (blockFrames.toDouble())
            if (meanSquare > 0.0) blockLoudness.add(-0.691 + 10.0 * log10(meanSquare))
        }
        stepIndex = (stepIndex + 1) % 4
        stepSums[stepIndex] = 0.0
        framesInStep = 0
    }

    /** Интегральная громкость в LUFS, или null если стробирование не оставило ни одного блока
     * (трек короче 400 мс либо целиком тише -70 LUFS). */
    fun integratedLufs(): Double? {
        val aboveAbsolute = blockLoudness.filter { it > ABSOLUTE_GATE_LKFS }
        if (aboveAbsolute.isEmpty()) return null
        // Относительный порог: -10 LU от громкости, посчитанной по блокам выше абсолютного порога.
        val relativeGate = meanLoudness(aboveAbsolute) - 10.0
        val gated = aboveAbsolute.filter { it > relativeGate }
        if (gated.isEmpty()) return null
        return meanLoudness(gated)
    }

    /** Усреднение идёт по энергии, а не по децибелам: среднее арифметическое дБ - другая величина. */
    private fun meanLoudness(blocks: List<Double>): Double {
        var sum = 0.0
        for (b in blocks) sum += 10.0.pow((b + 0.691) / 10.0)
        return -0.691 + 10.0 * log10(sum / blocks.size)
    }

    /** Истинный пик в dBTP (может быть > 0 при межсемпловых пиках). -inf возвращается как -120. */
    fun truePeakDbfs(): Double = if (truePeak <= 0f) -120.0 else 20.0 * log10(truePeak.toDouble())

    companion object {
        private const val ABSOLUTE_GATE_LKFS = -70.0

        /** Опорная громкость ReplayGain 2.0 - те же -18 LUFS, что и у прежнего приближения, так
         * что общая громкость библиотеки после пересчёта не поедет. */
        const val TARGET_LUFS = -18.0

        /** Потолок true-peak после применения gain. -1 dBTP - рекомендация EBU R128 s1 для
         * материала, который дальше пойдёт через лоссёвый кодек или чужой ЦАП. */
        const val TRUE_PEAK_CEILING_DBTP = -1.0

        /** Коэффициенты предфильтра (высокочастотная полка) BS.1770 для произвольной частоты
         * дискретизации. Спецификация даёт таблицу только для 48 кГц; здесь та же аналитическая
         * форма, из которой эта таблица и получена, - иначе на 44.1 кГц фильтр был бы не тот. */
        internal fun preFilterCoefficients(sampleRate: Int): DoubleArray {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(g / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return doubleArrayOf(
                (vh + vb * k / q + k * k) / a0,
                2.0 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0,
                2.0 * (k * k - 1.0) / a0,
                (1.0 - k / q + k * k) / a0,
            )
        }

        /** RLB - высокочастотный срез второго порядка, вторая ступень K-взвешивания. */
        internal fun rlbFilterCoefficients(sampleRate: Int): DoubleArray {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val denom = 1.0 + k / q + k * k
            return doubleArrayOf(
                1.0,
                -2.0,
                1.0,
                2.0 * (k * k - 1.0) / denom,
                (1.0 - k / q + k * k) / denom,
            )
        }
    }
}

/** Прямая форма II transposed. Коэффициенты: [b0, b1, b2, a1, a2] (a0 уже нормирован). */
internal class BiquadState {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double, c: DoubleArray): Double {
        val y = c[0] * x + z1
        z1 = c[1] * x - c[3] * y + z2
        z2 = c[2] * x - c[4] * y
        return y
    }
}

/** 4-кратная передискретизация полифазным FIR для поиска межсемпловых пиков (BS.1770-4 Annex 2).
 * Обычный пик по отсчётам такие пики не видит: между двумя семплами восстановленный сигнал может
 * заметно превышать 0 dBFS, и ЦАП/усилитель это слышит как искажение. */
internal class TruePeakInterpolator(private val channelCount: Int) {

    private val history = Array(channelCount) { FloatArray(TAPS_PER_PHASE) }
    private var writeIndex = 0

    /** Возвращает максимум по всем интерполированным точкам этого кадра, или null пока история
     * не набрана. */
    fun feed(samples: FloatArray, frameStart: Int): Float? {
        for (ch in 0 until channelCount) history[ch][writeIndex] = samples[frameStart + ch]
        writeIndex = (writeIndex + 1) % TAPS_PER_PHASE
        var peak = 0f
        for (ch in 0 until channelCount) {
            val h = history[ch]
            for (phase in 1 until PHASES) {
                var acc = 0f
                for (tap in 0 until TAPS_PER_PHASE) {
                    // writeIndex указывает на самый старый элемент кольца - он же тап с наибольшей
                    // задержкой.
                    val s = h[(writeIndex + tap) % TAPS_PER_PHASE]
                    acc += s * COEFFS[phase][TAPS_PER_PHASE - 1 - tap]
                }
                val a = abs(acc)
                if (a > peak) peak = a
            }
        }
        return peak
    }

    companion object {
        private const val PHASES = 4
        private const val TAPS_PER_PHASE = 12

        /** Окно Блэкмана на sinc - обычный низкочастотный фильтр интерполятора. Фаза 0 - это сам
         * исходный отсчёт (её и не считаем, он уже учтён обычным пиком). */
        private val COEFFS: Array<FloatArray> = run {
            val length = PHASES * TAPS_PER_PHASE
            val center = (length - 1) / 2.0
            val raw = DoubleArray(length) { n ->
                val x = (n - center) / PHASES
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val w = 0.42 - 0.5 * kotlin.math.cos(2 * PI * n / (length - 1)) +
                    0.08 * kotlin.math.cos(4 * PI * n / (length - 1))
                sinc * w
            }
            Array(PHASES) { phase ->
                val taps = DoubleArray(TAPS_PER_PHASE) { m -> raw[m * PHASES + phase] }
                // Единичное усиление по постоянной составляющей в каждой фазе: иначе интерполятор
                // сам по себе поднимал бы или занижал измеренный пик.
                val sum = taps.sum()
                FloatArray(TAPS_PER_PHASE) { m -> (if (abs(sum) > 1e-9) taps[m] / sum else taps[m]).toFloat() }
            }
        }
    }
}
