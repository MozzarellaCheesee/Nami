package dev.nami.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.nami.player.replaygain.ReplayGainAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The check that would have caught the chipmunk bug class: a processor must emit exactly as many
 * frames as it was given, in the encoding it declared. A byte-count mismatch here is what reads as
 * "sped up and pitched up" once AudioTrack interprets the buffer. */
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
    fun `gain processor preserves frame count and applies the boost`() {
        val processor = ReplayGainAudioProcessor()
        processor.boostDb = 6f // ~2x
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val input = stereoBuffer(100, -100, 1000, -1000)
        processor.queueInput(input)
        val output = processor.output

        assertEquals(8, output.remaining(), "output must be the same byte count as the input")
        val shorts = output.asShortBuffer()
        assertEquals(200, shorts.get().toInt())
        assertEquals(-200, shorts.get().toInt())
        assertEquals(1995, shorts.get().toInt())
        assertEquals(-1995, shorts.get().toInt())
    }

    @Test
    fun `gain processor clips instead of wrapping`() {
        val processor = ReplayGainAudioProcessor()
        processor.boostDb = 6f
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        processor.queueInput(stereoBuffer(30000, -30000))
        val shorts = processor.output.asShortBuffer()
        assertEquals(32767, shorts.get().toInt())
        assertEquals(-32768, shorts.get().toInt())
    }
}
