package dev.nami.player.replaygain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Проверка по эталонным сигналам EBU Tech 3341 (сами WAV-файлы EBU распространяет отдельно и в
 * репозиторий их класть незачем - сигналы там синтетические и воспроизводятся формулой один в
 * один: синус заданной частоты и амплитуды, тишина, ступеньки уровня). Допуск ±0.1 LU - тот же,
 * что задан в самом Tech 3341 для соответствия. */
class R128LoudnessTest {

    private fun sine(
        sampleRate: Int,
        channels: Int,
        seconds: Double,
        freqHz: Double,
        peakAmplitude: Double,
        phase: Double = 0.0,
    ): FloatArray {
        val frames = (sampleRate * seconds).toInt()
        val out = FloatArray(frames * channels)
        for (n in 0 until frames) {
            val v = (peakAmplitude * sin(2 * PI * freqHz * n / sampleRate + phase)).toFloat()
            for (c in 0 until channels) out[n * channels + c] = v
        }
        return out
    }

    private fun measure(samples: FloatArray, sampleRate: Int, channels: Int): R128Loudness =
        R128Loudness(sampleRate, channels).apply { feed(samples, samples.size) }

    /** Tech 3341, тест 1: стерео-синус 1 кГц с пиковой амплитудой -23 dBFS даёт ровно -23.0 LUFS.
     * Это и есть смысл смещения -0.691 в BS.1770 - оно скомпенсировано так, что 1 кГц читается
     * как есть. Если K-взвешивание собрано неправильно, этот тест промахнётся сразу. */
    @Test
    fun `EBU 3341 case 1 - 1kHz sine at -23 dBFS reads -23 LUFS`() {
        for (rate in listOf(44100, 48000)) {
            val amp = 10.0.pow(-23.0 / 20.0)
            val lufs = measure(sine(rate, 2, 20.0, 1000.0, amp), rate, 2).integratedLufs()
            assertNotNull(lufs, "нет результата на $rate Гц")
            assertTrue(abs(lufs - (-23.0)) < 0.1, "$rate Гц: получили $lufs, ждали -23.0")
        }
    }

    /** Tech 3341, тест 2: тот же сигнал на -33 dBFS даёт -33.0 LUFS - проверяет, что шкала
     * линейна, а не только откалибрована в одной точке. */
    @Test
    fun `EBU 3341 case 2 - 1kHz sine at -33 dBFS reads -33 LUFS`() {
        val amp = 10.0.pow(-33.0 / 20.0)
        val lufs = measure(sine(48000, 2, 20.0, 1000.0, amp), 48000, 2).integratedLufs()
        assertNotNull(lufs)
        assertTrue(abs(lufs - (-33.0)) < 0.1, "получили $lufs, ждали -33.0")
    }

    /** Стробирование: 10 секунд сигнала -23 LUFS плюс 10 секунд тишины должны дать те же
     * -23 LUFS. Прежний простой RMS занизил бы результат примерно на 3 дБ - тишина входила в
     * среднее. Это и есть та ошибка, ради которой стробирование в стандарте существует. */
    @Test
    fun `gating excludes silence`() {
        val rate = 48000
        val amp = 10.0.pow(-23.0 / 20.0)
        val tone = sine(rate, 2, 10.0, 1000.0, amp)
        val silence = FloatArray(rate * 2 * 10)
        val meter = R128Loudness(rate, 2)
        meter.feed(tone, tone.size)
        meter.feed(silence, silence.size)
        val lufs = meter.integratedLufs()
        assertNotNull(lufs)
        assertTrue(abs(lufs - (-23.0)) < 0.1, "получили $lufs, ждали -23.0")
    }

    /** True-peak: синус на четверти частоты дискретизации со сдвигом фазы на 45° попадает
     * отсчётами ровно в ±0.7071 (пик по семплам -3 dBFS), хотя сам сигнал доходит до 0 dBFS.
     * Обычное пиковое измерение этот межсемпловый пик не видит - передискретизация видит. */
    @Test
    fun `true peak sees the intersample peak a sample peak misses`() {
        val rate = 48000
        val samples = sine(rate, 1, 2.0, rate / 4.0, 1.0, phase = PI / 4)
        var samplePeak = 0f
        for (s in samples) if (abs(s) > samplePeak) samplePeak = abs(s)
        assertTrue(abs(samplePeak - 0.7071f) < 0.01f, "пик по семплам должен быть ~0.707, а не $samplePeak")

        val truePeak = measure(samples, rate, 1).truePeakDbfs()
        assertTrue(truePeak > -0.6, "true-peak должен подойти к 0 dBTP, получили $truePeak")
        assertTrue(truePeak < 0.5, "и не должен уехать выше сигнала, получили $truePeak")
    }

    /** Собственно решение о gain: слишком тихий трек нельзя поднимать до -18 LUFS, если это
     * загонит true-peak выше -1 dBTP. */
    @Test
    fun `gain is capped by true peak headroom`() {
        // Хочется +10 дБ, но пик уже на -2 dBTP - разрешён только +1.
        val gain = ReplayGainScanner.gainFor(integratedLufs = -28.0, truePeakDbtp = -2.0)
        assertTrue(abs(gain - 1f) < 0.01f, "получили $gain, ждали +1.0")
    }

    @Test
    fun `loud track is attenuated regardless of headroom`() {
        val gain = ReplayGainScanner.gainFor(integratedLufs = -8.0, truePeakDbtp = 0.0)
        assertTrue(abs(gain - (-10f)) < 0.01f, "получили $gain, ждали -10.0")
    }
}
