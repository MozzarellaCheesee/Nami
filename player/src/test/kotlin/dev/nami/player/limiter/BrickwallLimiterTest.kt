package dev.nami.player.limiter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrickwallLimiterTest {

    private fun limiter(): BrickwallLimiter = BrickwallLimiter().apply { configure(48000) }

    /** Главное свойство: что бы ни пришло на вход, на выходе ни один отсчёт не перелезает через
     * потолок. Без этого квартет "EQ + свёртка + кроссфид + усиление" упирался бы в жёсткое
     * обрезание в квантователе, то есть в треск. */
    @Test
    fun `nothing exceeds the ceiling`() {
        val limiter = limiter()
        // Синус, разогнанный вдвое выше полной шкалы - типичный итог EQ с плюсовыми полосами.
        val frames = 48000
        val samples = FloatArray(frames * 2)
        for (n in 0 until frames) {
            val v = (2.0 * sin(2 * PI * 440.0 * n / 48000)).toFloat()
            samples[n * 2] = v
            samples[n * 2 + 1] = v
        }
        limiter.process(samples, samples.size, 2)
        val peak = samples.maxOf { abs(it) }
        assertTrue(peak <= BrickwallLimiter.CEILING + 1e-6f, "пик $peak выше потолка ${BrickwallLimiter.CEILING}")
    }

    /** Не менее важное: на сигнале, который и так не доходит до потолка, лимитер обязан быть
     * полностью прозрачным - иначе "включён всегда" было бы неприемлемо. */
    @Test
    fun `quiet signal passes through bit-identical`() {
        val limiter = limiter()
        val frames = 4800
        val samples = FloatArray(frames * 2)
        for (n in 0 until frames) {
            val v = (0.5 * sin(2 * PI * 440.0 * n / 48000)).toFloat()
            samples[n * 2] = v
            samples[n * 2 + 1] = v
        }
        val original = samples.copyOf()
        limiter.process(samples, samples.size, 2)
        for (i in samples.indices) assertEquals(original[i], samples[i], "отсчёт $i изменён")
    }

    /** Каналы связаны одним множителем: пик только в левом не должен оставить правый нетронутым,
     * иначе на каждом громком месте съезжал бы стереообраз. */
    @Test
    fun `channels share one gain`() {
        val limiter = limiter()
        val samples = floatArrayOf(2f, 0.4f)
        limiter.process(samples, 2, 2)
        val applied = samples[0] / 2f
        assertTrue(abs(samples[1] - 0.4f * applied) < 1e-6f, "правый канал не получил тот же множитель")
        assertTrue(samples[0] <= BrickwallLimiter.CEILING + 1e-6f)
    }

    /** После пика громкость обязана вернуться, а не просесть до конца трека. */
    @Test
    fun `gain releases back to unity`() {
        val limiter = limiter()
        limiter.process(floatArrayOf(3f, 3f), 2, 2)
        // 0.5 с при постоянной времени 50 мс - десять постоянных, вернуться должно полностью.
        val quiet = FloatArray(24000 * 2) { 0.2f }
        limiter.process(quiet, quiet.size, 2)
        assertTrue(abs(quiet.last() - 0.2f) < 1e-4f, "множитель не вернулся к единице: ${quiet.last()}")
    }
}
