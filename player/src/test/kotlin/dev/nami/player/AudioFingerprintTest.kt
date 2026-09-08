package dev.nami.player

import dev.nami.player.waveform.AudioFingerprint
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Смысл отпечатка - "тот же трек в другом формате даёт тот же хеш, другой трек - другой".
 * Декодер сюда не нужен: [AudioFingerprint.fromEnvelope] принимает готовую огибающую, поэтому
 * "перекодирование" тут моделируется шумом и сдвигом громкости поверх той же формы. */
class AudioFingerprintTest {

    private fun envelope(seed: Int, size: Int = 120): List<Float> =
        List(size) { i -> 0.5f + 0.4f * sin((i + seed) * 0.37).toFloat() }

    @Test
    fun `слишком короткая огибающая - null, а не хеш из мусора`() {
        assertNull(AudioFingerprint.fromEnvelope(List(10) { 0.5f }))
    }

    @Test
    fun `одна и та же огибающая даёт один и тот же отпечаток`() {
        assertEquals(AudioFingerprint.fromEnvelope(envelope(0)), AudioFingerprint.fromEnvelope(envelope(0)))
    }

    @Test
    fun `громкость не влияет - отпечаток строится на разностях, а не на уровнях`() {
        val quiet = envelope(0)
        val loud = quiet.map { it * 0.3f }
        assertEquals(AudioFingerprint.fromEnvelope(quiet), AudioFingerprint.fromEnvelope(loud))
    }

    @Test
    fun `небольшой шум перекодирования остаётся в пределах порога`() {
        val original = AudioFingerprint.fromEnvelope(envelope(0))!!
        // Мелкая дрожь уровней, какую даёт перекодирование - форма та же.
        val noisy = AudioFingerprint.fromEnvelope(envelope(0).mapIndexed { i, v -> v + if (i % 17 == 0) 0.01f else 0f })!!
        assertTrue(
            AudioFingerprint.matches(original, noisy),
            "расстояние ${AudioFingerprint.distance(original, noisy)} больше порога",
        )
    }

    @Test
    fun `другая форма - другой отпечаток и мимо порога`() {
        val a = AudioFingerprint.fromEnvelope(envelope(0))!!
        val b = AudioFingerprint.fromEnvelope(envelope(7))!!
        assertNotEquals(a, b)
        assertTrue(!AudioFingerprint.matches(a, b), "расстояние ${AudioFingerprint.distance(a, b)}")
    }

    @Test
    fun `расстояние считается по битам`() {
        assertEquals(0, AudioFingerprint.distance(0b1011L, 0b1011L))
        assertEquals(2, AudioFingerprint.distance(0b1011L, 0b1110L))
    }
}
