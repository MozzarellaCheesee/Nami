package dev.nami.player.analysis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class FftTest {

    @Test
    fun `pure sine wave peaks at its own bin`() {
        val n = 64
        val binIndex = 8 // bin k corresponds to k cycles over n samples
        val real = DoubleArray(n) { i -> sin(2 * PI * binIndex * i / n) }
        val imag = DoubleArray(n)

        Fft.transform(real, imag)

        val magnitudes = (0 until n / 2).map { k -> sqrt(real[k] * real[k] + imag[k] * imag[k]) }
        val peakBin = magnitudes.indices.maxByOrNull { magnitudes[it] }

        assertEquals(binIndex, peakBin)
    }

    @Test
    fun `DC signal only has energy in bin 0`() {
        val n = 32
        val real = DoubleArray(n) { 1.0 }
        val imag = DoubleArray(n)

        Fft.transform(real, imag)

        val bin0Magnitude = sqrt(real[0] * real[0] + imag[0] * imag[0])
        val otherBinsMagnitude = (1 until n).sumOf { sqrt(real[it] * real[it] + imag[it] * imag[it]) }

        assertEquals(n.toDouble(), bin0Magnitude, 1e-9)
        assertEquals(0.0, otherBinsMagnitude, 1e-6)
    }
}
