package dev.nami.player.convolution

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.nami.player.analysis.Fft
import dev.nami.player.toPcm16
import java.nio.ByteBuffer

/** П.md §9 "свёртка с импульсной характеристикой" - равномерно секционированная свёртка методом
 * overlap-save на БПФ.
 *
 * Почему не «в лоб»: прямая свёртка стоит O(M) умножений на сэмпл, а импульс комнаты - это
 * десятки тысяч отсчётов (1 с при 48 кГц = 48000). На 48000 умножений на сэмпл ни один телефон
 * не успеет. БПФ-свёртка блоками стоит O(log N) на сэмпл, то есть на три порядка меньше.
 *
 * Почему секционированная, а не один большой БПФ на весь импульс: неразбитый импульс требует БПФ
 * размером с весь блок обработки, то есть задержки в целый импульс (секунда и больше) - плеер
 * реагировал бы на паузу через секунду. Разбиение импульса на [BLOCK] отсчётов оставляет задержку
 * ровно в один блок (~46 мс при 44.1 кГц), а стоимость почти не растёт.
 *
 * Как работает overlap-save: вход накапливается блоками по [BLOCK]. Для очередного блока берём
 * окно из [FFT_SIZE] = 2*[BLOCK] отсчётов (предыдущий блок + текущий), делаем БПФ, умножаем на
 * спектр каждой секции импульса, суммируем со сдвигом по «частотной линии задержки» и делаем
 * обратное БПФ. Первая половина результата испорчена круговой свёрткой (потому метод и
 * «save» - её отбрасывают), вторая половина - корректные [BLOCK] отсчётов линейной свёртки.
 *
 * Задержка в один блок - причина, по которой процессор выпадает из цепочки при выключенном
 * тумблере, а не пропускает сигнал насквозь: иначе он тянул бы 46 мс задержки даже будучи
 * «выключенным».
 *
 * ponytail: БПФ комплексный на DoubleArray и переиспользует dev.nami.player.analysis.Fft. Для
 * вещественного сигнала половина работы лишняя (real-FFT дал бы примерно 2x), и на очень длинных
 * импульсах вместе с числом каналов это может упереться в CPU на слабом телефоне. Менять на
 * real-FFT есть смысл, только если это реально проявится - см. [MAX_PARTITIONS] как страховку. */
class ConvolutionAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false

    /** Импульс, выбранный пользователем. Меняется из настроек; аудиопоток подхватит его на
     * следующей перестройке конвейера (см. isActive/onConfigure - тот же приём, что у EQ). */
    @Volatile var impulseResponse: ImpulseResponse? = null

    /** Доля обработанного сигнала в выходе, 0..1 («сухой/мокрый»). Читается на каждом блоке, так
     * что ползунок в настройках работает вживую, без перестройки конвейера. */
    @Volatile var mix: Float = 1f

    private var sampleRateHz = 0
    private var channelCount = 0

    // Спектры секций импульса: [канал][секция][re/im], каждый длиной FFT_SIZE.
    private var irSpectraRe: Array<Array<DoubleArray>> = emptyArray()
    private var irSpectraIm: Array<Array<DoubleArray>> = emptyArray()
    private var partitionCount = 0

    // Частотная линия задержки: спектры последних partitionCount входных блоков, по каналам.
    private var fdlRe: Array<Array<DoubleArray>> = emptyArray()
    private var fdlIm: Array<Array<DoubleArray>> = emptyArray()
    private var fdlPos = 0

    // Незаполненный «хвост» входа между вызовами queueInput: медиа отдаёт буферы произвольного
    // размера, а обрабатываем мы строго блоками по BLOCK кадров.
    private var pending: Array<FloatArray> = emptyArray()
    private var pendingCount = 0

    // Предыдущий блок каждого канала - первая половина окна overlap-save.
    private var previousBlock: Array<FloatArray> = emptyArray()

    // Рабочие буферы одного блока, чтобы не аллоцировать в аудиопотоке.
    private var workRe = DoubleArray(0)
    private var workIm = DoubleArray(0)
    private var accRe = DoubleArray(0)
    private var accIm = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        prepareImpulse()
        return inputAudioFormat
    }

    /** Раскладывает импульс по секциям и считает их спектры - один раз на конфигурацию, а не на
     * блок: это самая дорогая часть, и она не зависит от входного сигнала. */
    private fun prepareImpulse() {
        val ir = impulseResponse
        partitionCount = 0
        if (ir == null || channelCount <= 0 || sampleRateHz <= 0) return

        // Импульс, записанный на другой частоте дискретизации, надо привести к частоте потока:
        // иначе комната «съедет» по времени и по спектру. Линейная интерполяция - осознанное
        // упрощение: она чуть заваливает верх, но импульс и так сглажен, а ставить ради этого
        // полноценный ресемплер значит тащить sinc-интерполяцию в модуль ради разницы, которую
        // на хвосте реверберации не слышно.
        val resampled = ir.channels.map { resample(it, ir.sampleRateHz, sampleRateHz) }
        val irLength = resampled.firstOrNull()?.size ?: return
        if (irLength == 0) return

        partitionCount = ((irLength + BLOCK - 1) / BLOCK).coerceAtMost(MAX_PARTITIONS)

        // Каналов в импульсе может быть меньше, чем в потоке (моно-IR - самый частый случай):
        // тогда один и тот же импульс идёт на все каналы.
        irSpectraRe = Array(channelCount) { channel ->
            val source = resampled[channel % resampled.size]
            Array(partitionCount) { partition ->
                DoubleArray(FFT_SIZE) { i ->
                    // Секция кладётся в первую половину окна, вторая - нули: так круговая свёртка
                    // окна длиной FFT_SIZE совпадает с линейной для блока в BLOCK отсчётов.
                    if (i < BLOCK) source.getOrElse(partition * BLOCK + i) { 0f }.toDouble() else 0.0
                }
            }
        }
        irSpectraIm = Array(channelCount) { Array(partitionCount) { DoubleArray(FFT_SIZE) } }
        for (channel in 0 until channelCount) {
            for (partition in 0 until partitionCount) {
                Fft.transform(irSpectraRe[channel][partition], irSpectraIm[channel][partition])
            }
        }

        fdlRe = Array(channelCount) { Array(partitionCount) { DoubleArray(FFT_SIZE) } }
        fdlIm = Array(channelCount) { Array(partitionCount) { DoubleArray(FFT_SIZE) } }
        fdlPos = 0
        pending = Array(channelCount) { FloatArray(BLOCK) }
        pendingCount = 0
        previousBlock = Array(channelCount) { FloatArray(BLOCK) }
        workRe = DoubleArray(FFT_SIZE)
        workIm = DoubleArray(FFT_SIZE)
        accRe = DoubleArray(FFT_SIZE)
        accIm = DoubleArray(FFT_SIZE)
    }

    private fun resample(source: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || fromRate <= 0) return source
        val ratio = toRate.toDouble() / fromRate
        val outLength = (source.size * ratio).toInt().coerceAtMost(IrWavLoader.MAX_SAMPLES_PER_CHANNEL)
        if (outLength <= 0) return FloatArray(0)
        return FloatArray(outLength) { i ->
            val position = i / ratio
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val a = source.getOrElse(index) { 0f }
            val b = source.getOrElse(index + 1) { 0f }
            a + (b - a) * fraction
        }
    }

    override fun isActive(): Boolean = enabled && partitionCount > 0 && sampleRateHz > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val inShorts = inputBuffer.asShortBuffer()
        val frameCount = remaining / 2 / channelCount

        // Сколько целых блоков сможем выдать с учётом уже накопленного хвоста. Выход короче
        // входа на неполный блок - это разрешено контрактом AudioProcessor (так же ведёт себя
        // штатный пропуск тишины), а недостающее догоняется на следующих буферах.
        val totalFrames = pendingCount + frameCount
        val blocks = totalFrames / BLOCK
        val output = replaceOutputBuffer(blocks * BLOCK * channelCount * 2)
        val outShorts = output.asShortBuffer()

        var framesRead = 0
        while (framesRead < frameCount) {
            // Добираем pending до полного блока.
            val take = minOf(BLOCK - pendingCount, frameCount - framesRead)
            for (i in 0 until take) {
                for (channel in 0 until channelCount) {
                    pending[channel][pendingCount + i] = inShorts.get().toFloat()
                }
            }
            pendingCount += take
            framesRead += take
            if (pendingCount < BLOCK) break

            processBlock()
            // Блок обработан на месте в pending - выкладываем и запоминаем как «предыдущий».
            for (i in 0 until BLOCK) {
                for (channel in 0 until channelCount) {
                    outShorts.put(pending[channel][i].toPcm16())
                }
            }
            pendingCount = 0
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(blocks * BLOCK * channelCount * 2).flip()
    }

    /** Конец потока: в pending обычно лежит неполный блок. Без этого хвост до 46 мс просто
     * пропадал бы в конце каждого трека - на гэплесс-переходе это слышно как обрезанное слово.
     * Добиваем нулями, считаем блок как обычно и отдаём ровно столько кадров, сколько было. */
    override fun onQueueEndOfStream() {
        val tail = pendingCount
        if (tail > 0 && partitionCount > 0) {
            for (channel in 0 until channelCount) {
                java.util.Arrays.fill(pending[channel], tail, BLOCK, 0f)
            }
            processBlock()
            val output = replaceOutputBuffer(tail * channelCount * 2)
            val outShorts = output.asShortBuffer()
            for (i in 0 until tail) {
                for (channel in 0 until channelCount) {
                    outShorts.put(pending[channel][i].toPcm16())
                }
            }
            output.position(tail * channelCount * 2).flip()
            pendingCount = 0
        }
    }

    /** Свёртка одного блока. На входе pending[канал] - BLOCK свежих отсчётов, на выходе там же
     * результат; previousBlock обновляется для следующего окна. */
    private fun processBlock() {
        val currentMix = mix.coerceIn(0f, 1f)
        for (channel in 0 until channelCount) {
            val current = pending[channel]
            val previous = previousBlock[channel]

            // Окно overlap-save: [предыдущий блок | текущий блок].
            for (i in 0 until BLOCK) {
                workRe[i] = previous[i].toDouble()
                workRe[BLOCK + i] = current[i].toDouble()
            }
            java.util.Arrays.fill(workIm, 0.0)
            Fft.transform(workRe, workIm)

            // Свежий спектр входа занимает текущую позицию линии задержки.
            System.arraycopy(workRe, 0, fdlRe[channel][fdlPos], 0, FFT_SIZE)
            System.arraycopy(workIm, 0, fdlIm[channel][fdlPos], 0, FFT_SIZE)

            java.util.Arrays.fill(accRe, 0.0)
            java.util.Arrays.fill(accIm, 0.0)
            // Секция p импульса умножается на вход, отстоящий на p блоков назад - это и есть
            // сложение задержанных копий, только в частотной области.
            for (partition in 0 until partitionCount) {
                val slot = ((fdlPos - partition) % partitionCount + partitionCount) % partitionCount
                val xRe = fdlRe[channel][slot]
                val xIm = fdlIm[channel][slot]
                val hRe = irSpectraRe[channel][partition]
                val hIm = irSpectraIm[channel][partition]
                for (i in 0 until FFT_SIZE) {
                    accRe[i] += xRe[i] * hRe[i] - xIm[i] * hIm[i]
                    accIm[i] += xRe[i] * hIm[i] + xIm[i] * hRe[i]
                }
            }

            inverseTransform(accRe, accIm)

            // Вторая половина окна - корректная линейная свёртка, первая испорчена заворотом.
            for (i in 0 until BLOCK) {
                val wet = accRe[BLOCK + i].toFloat()
                previous[i] = current[i]
                current[i] = current[i] * (1f - currentMix) + wet * currentMix
            }
        }
        fdlPos = (fdlPos + 1) % partitionCount
    }

    /** Обратное БПФ через прямое: conj -> forward -> conj -> /N. Так переиспользуется уже
     * существующий [Fft], вместо второй копии тех же бабочек с другим знаком. */
    private fun inverseTransform(real: DoubleArray, imag: DoubleArray) {
        for (i in imag.indices) imag[i] = -imag[i]
        Fft.transform(real, imag)
        val scale = 1.0 / real.size
        for (i in real.indices) {
            real[i] *= scale
            imag[i] = -imag[i] * scale
        }
    }

    override fun onFlush() {
        // После перемотки «хвост» реверберации относится к другому месту трека - донести его до
        // новой позиции значит наложить эхо от того, что уже не звучит.
        pendingCount = 0
        previousBlock.forEach { it.fill(0f) }
        fdlRe.forEach { partitions -> partitions.forEach { it.fill(0.0) } }
        fdlIm.forEach { partitions -> partitions.forEach { it.fill(0.0) } }
        fdlPos = 0
    }

    override fun onReset() {
        sampleRateHz = 0
        channelCount = 0
        partitionCount = 0
        irSpectraRe = emptyArray()
        irSpectraIm = emptyArray()
        fdlRe = emptyArray()
        fdlIm = emptyArray()
        pending = emptyArray()
        previousBlock = emptyArray()
    }

    companion object {
        /** Кадров в блоке обработки. 2048 при 44.1 кГц - это ~46 мс задержки: заметно для игры,
         * но не для прослушивания, а меньший блок поднял бы долю БПФ в общей стоимости. */
        const val BLOCK = 2048
        const val FFT_SIZE = BLOCK * 2

        /** Потолок длины импульса в блоках (~4.6 с при 44.1 кГц). Стоимость линейна по числу
         * секций, и без потолка десятисекундный импульс на слабом телефоне съел бы аудиопоток
         * целиком - лучше обрезать хвост реверберации, чем заикаться. */
        const val MAX_PARTITIONS = 100
    }
}
