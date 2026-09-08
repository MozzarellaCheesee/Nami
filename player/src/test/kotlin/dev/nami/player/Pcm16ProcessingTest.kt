package dev.nami.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.nami.player.dither.DitherAudioProcessor
import dev.nami.player.replaygain.ReplayGainAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The check that would have caught the chipmunk bug class: a processor must emit exactly as many
 * FRAMES as it was given, in the encoding it declared. A frame-count mismatch here is what reads as
 * "sped up and pitched up" once AudioTrack interprets the buffer.
 *
 * Байты больше не обязаны совпадать с входом: стадии DSP отдают float (4 байта на отсчёт вместо
 * двух), см. Pcm16.kt. Сохраняется именно число кадров и объявленная кодировка - проверяем
 * ровно это, потому что подводило всегда оно. */
class Pcm16ProcessingTest {

    private fun stereoBuffer(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    @Test
    fun `clamp rounds and saturates`() {
        assertEquals(1.toShort(), 0.6f.toPcm16())
        assertEquals(0.toShort(), 0.4f.toPcm16())
        assertEquals(32767.toShort(), 99999f.toPcm16())
        assertEquals((-32768).toShort(), (-99999f).toPcm16())
    }

    @Test
    fun `нормализованное преобразование округляет и ограничивает`() {
        assertEquals(32767.toShort(), 2f.normalizedToPcm16())
        assertEquals((-32768).toShort(), (-2f).normalizedToPcm16())
        assertEquals(16384.toShort(), 0.5f.normalizedToPcm16())
        assertEquals(0.toShort(), 0f.normalizedToPcm16())
    }

    @Test
    fun `gain processor preserves frame count and applies the boost`() {
        val processor = ReplayGainAudioProcessor()
        processor.boostDb = 6f // ~2x
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val outputFormat = processor.configure(format)
        processor.flush()

        assertEquals(C.ENCODING_PCM_FLOAT, outputFormat.encoding, "стадия DSP обязана объявлять float")

        val input = stereoBuffer(100, -100, 1000, -1000)
        processor.queueInput(input)
        val output = processor.output

        // Четыре отсчёта на входе - четыре на выходе, но уже по 4 байта каждый.
        assertEquals(16, output.remaining(), "число кадров должно сохраниться при float-выходе")
        val floats = output.asFloatBuffer()
        val expected = floatArrayOf(200f, -200f, 1995f, -1995f)
        for (value in expected) {
            assertTrue(abs(floats.get() * 32768f - value) <= 1f, "ожидалось около $value")
        }
    }

    /** Раньше усиление само упиралось в потолок int16. Теперь оно этого не делает намеренно:
     * ограничение - работа замыкающего квантователя, и клип должен случиться ровно один раз, в
     * конце цепочки, а не на каждой стадии. */
    @Test
    fun `клип делает замыкающий квантователь, а не усиление`() {
        val gain = ReplayGainAudioProcessor()
        gain.boostDb = 6f
        gain.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        gain.flush()
        gain.queueInput(stereoBuffer(30000, -30000))
        val afterGain = gain.output

        // Усиление выпустило значение за пределы ±1.0 и не срезало его.
        val peek = afterGain.duplicate().order(afterGain.order()).asFloatBuffer().get()
        assertTrue(peek > 1f, "усиление не должно ограничивать само, было $peek")

        val quantizer = DitherAudioProcessor()
        val outputFormat = quantizer.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
        quantizer.flush()
        assertEquals(C.ENCODING_PCM_16BIT, outputFormat.encoding, "цепочка обязана заканчиваться int16")
        assertTrue(quantizer.isActive, "на float-входе квантователь обязан быть активен")

        quantizer.queueInput(afterGain)
        val out = quantizer.output
        assertEquals(4, out.remaining(), "два стереокадра int16 - это 4 байта")
        val shorts = out.asShortBuffer()
        assertEquals(32767, shorts.get().toInt())
        assertEquals(-32768, shorts.get().toInt())
    }

    /** Без дизера и на 16-битном входе квантователю нечего делать - он обязан выпасть из
     * цепочки, иначе тракт лишний раз округлял бы уже округлённое. */
    @Test
    fun `квантователь неактивен на int16 входе без дизера`() {
        val quantizer = DitherAudioProcessor()
        quantizer.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        assertTrue(!quantizer.isActive)
        quantizer.enabled = true
        assertTrue(quantizer.isActive, "с включённым дизером - активен")
    }
}
