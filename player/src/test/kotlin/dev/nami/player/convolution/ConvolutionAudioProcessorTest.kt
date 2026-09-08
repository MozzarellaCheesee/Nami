package dev.nami.player.convolution

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Свёртка - тот случай, где «работает» и «правильно» на слух не различить: неверная склейка
 * блоков или сдвиг на один отсчёт звучат просто как «немного другая комната». Поэтому проверяем
 * не сам факт звука, а совпадение с прямой свёрткой, посчитанной в лоб по определению. */
class ConvolutionAudioProcessorTest {

    private val sampleRate = 44100

    /** Прогоняет сигнал через процессор моно-каналом и возвращает то, что он выдал. */
    private fun process(input: FloatArray, ir: FloatArray): FloatArray {
        val processor = ConvolutionAudioProcessor().apply {
            enabled = true
            impulseResponse = ImpulseResponse(sampleRate, listOf(ir))
        }
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertTrue(processor.isActive, "процессор должен быть активен с непустым импульсом")

        val inputBuffer = ByteBuffer.allocate(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { inputBuffer.putShort(it.toInt().toShort()) }
        inputBuffer.flip()
        processor.queueInput(inputBuffer)

        // Стадии DSP отдают нормализованный float (см. Pcm16.kt) - домножаем обратно на полную
        // шкалу, чтобы сравнивать в тех же единицах, в которых задан вход.
        val out = ArrayList<Float>()
        fun drain() {
            val buffer = processor.output
            val floats = buffer.asFloatBuffer()
            while (floats.hasRemaining()) out.add(floats.get() * 32768f)
        }
        drain()
        processor.queueEndOfStream()
        drain()
        return out.toFloatArray()
    }

    /** Свёртка по определению - эталон, с которым сравнивается быстрая. */
    private fun directConvolution(input: FloatArray, ir: FloatArray): FloatArray =
        FloatArray(input.size) { n ->
            var sum = 0.0
            for (k in ir.indices) {
                if (n - k >= 0) sum += ir[k].toDouble() * input[n - k]
            }
            sum.toFloat()
        }

    @Test
    fun `дельта-импульс не меняет сигнал`() {
        val input = FloatArray(4 * ConvolutionAudioProcessor.BLOCK) { (it % 500 - 250).toFloat() }
        val output = process(input, floatArrayOf(1f))
        assertEquals(input.size, output.size)
        for (i in input.indices) {
            assertTrue(abs(output[i] - input[i]) <= 1f, "отсчёт $i: ${output[i]} против ${input[i]}")
        }
    }

    @Test
    fun `сдвинутая дельта задерживает сигнал ровно на один отсчёт`() {
        val input = FloatArray(2 * ConvolutionAudioProcessor.BLOCK) { (it % 300 - 150).toFloat() }
        val output = process(input, floatArrayOf(0f, 1f))
        for (i in 1 until input.size) {
            assertTrue(abs(output[i] - input[i - 1]) <= 1f, "отсчёт $i")
        }
    }

    /** Импульс короче блока - одна секция, проверяется сама БПФ-свёртка и склейка overlap-save. */
    @Test
    fun `короткий импульс совпадает с прямой свёрткой`() {
        val random = Random(1)
        val input = FloatArray(4 * ConvolutionAudioProcessor.BLOCK) { (random.nextInt(-1000, 1000)).toFloat() }
        val ir = FloatArray(100) { (random.nextFloat() - 0.5f) * 0.1f }
        val output = process(input, ir)
        val expected = directConvolution(input, ir)
        for (i in expected.indices) {
            assertTrue(abs(output[i] - expected[i]) <= 2f, "отсчёт $i: ${output[i]} против ${expected[i]}")
        }
    }

    /** Импульс длиннее блока - несколько секций, то есть проверяется ещё и частотная линия
     * задержки: именно её легко сдвинуть на блок и не заметить на слух. */
    @Test
    fun `длинный импульс на несколько секций совпадает с прямой свёрткой`() {
        val random = Random(7)
        val input = FloatArray(6 * ConvolutionAudioProcessor.BLOCK) { (random.nextInt(-800, 800)).toFloat() }
        val ir = FloatArray(3000) { (random.nextFloat() - 0.5f) * 0.02f }
        val output = process(input, ir)
        val expected = directConvolution(input, ir)
        for (i in expected.indices) {
            assertTrue(abs(output[i] - expected[i]) <= 2f, "отсчёт $i: ${output[i]} против ${expected[i]}")
        }
    }

    @Test
    fun `без импульса процессор выключен и не трогает поток`() {
        val processor = ConvolutionAudioProcessor().apply { enabled = true }
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        assertTrue(!processor.isActive, "без импульса свёртке нечего делать")
    }

    @Test
    fun `mix ноль оставляет сухой сигнал`() {
        val input = FloatArray(2 * ConvolutionAudioProcessor.BLOCK) { (it % 200 - 100).toFloat() }
        val processor = ConvolutionAudioProcessor().apply {
            enabled = true
            mix = 0f
            impulseResponse = ImpulseResponse(sampleRate, listOf(FloatArray(500) { 0.01f }))
        }
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val inputBuffer = ByteBuffer.allocate(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { inputBuffer.putShort(it.toInt().toShort()) }
        inputBuffer.flip()
        processor.queueInput(inputBuffer)
        val floats = processor.output.asFloatBuffer()
        var i = 0
        while (floats.hasRemaining()) {
            assertTrue(abs(floats.get() * 32768f - input[i]) <= 1f, "отсчёт $i должен быть нетронутым")
            i++
        }
    }
}

class IrWavLoaderTest {

    /** Собирает минимальный корректный 16-битный WAV - ровно то, что отдаёт любой редактор. */
    private fun wav16(sampleRate: Int, channels: Int, samples: List<Short>): ByteArray {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun loadBytes(bytes: ByteArray): ImpulseResponse? {
        // Префикс не короче трёх символов - требование createTempFile.
        val file = File.createTempFile("nami_ir", ".wav").apply { deleteOnExit() }
        file.writeBytes(bytes)
        return IrWavLoader.load(file)
    }

    @Test
    fun `читает моно 16 бит`() {
        val ir = loadBytes(wav16(48000, 1, listOf(1000, -2000, 3000, 0)))
        assertNotNull(ir)
        assertEquals(48000, ir.sampleRateHz)
        assertEquals(1, ir.channels.size)
        assertEquals(4, ir.lengthSamples)
        // Нормировка по энергии: абсолютные значения меняются, соотношение - нет.
        val channel = ir.channels[0]
        assertTrue(abs(channel[1] / channel[0] + 2f) < 0.01f, "соотношение отсчётов должно сохраниться")
    }

    @Test
    fun `читает стерео и разносит каналы`() {
        // Кадры чередуются L,R - если разбор перепутает порядок, каналы окажутся смешаны.
        val ir = loadBytes(wav16(44100, 2, listOf(1000, 0, 2000, 0, 3000, 0)))
        assertNotNull(ir)
        assertEquals(2, ir.channels.size)
        assertEquals(3, ir.lengthSamples)
        assertTrue(ir.channels[1].all { it == 0f }, "правый канал был нулевым и должен остаться нулевым")
    }

    @Test
    fun `нормировка приводит разную громкость к одному уровню`() {
        val quiet = loadBytes(wav16(44100, 1, listOf(100, -100, 100)))!!
        val loud = loadBytes(wav16(44100, 1, listOf(10000, -10000, 10000)))!!
        // Один и тот же импульс, записанный тише и громче, после нормировки должен совпасть -
        // иначе громкость трека зависела бы от того, как автор файла выставил уровень.
        for (i in 0 until 3) {
            assertTrue(abs(quiet.channels[0][i] - loud.channels[0][i]) < 0.01f, "отсчёт $i")
        }
    }

    @Test
    fun `мусор вместо WAV не выбрасывает исключение`() {
        assertNull(loadBytes(ByteArray(100) { it.toByte() }))
    }

    @Test
    fun `тишина вместо импульса отвергается`() {
        // Нулевой импульс превратил бы весь трек в тишину - лучше отказаться от файла.
        assertNull(loadBytes(wav16(44100, 1, listOf(0, 0, 0, 0))))
    }
}
