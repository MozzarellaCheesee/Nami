package dev.nami.player.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iterative radix-2 Cooley-Tukey FFT, in place. [real]/[imag] length must be a power of two --
 * callers own zero-padding/windowing before calling this. No allocation beyond the bit-reversal
 * swap and the twiddle factors, since this runs once per analysis frame (thousands of times per
 * track scanned). */
object Fft {
    fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
        if (n <= 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wReal = cos(angle)
            val wImag = sin(angle)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until len / 2) {
                    val evenReal = real[i + k]
                    val evenImag = imag[i + k]
                    val oddReal = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val oddImag = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal
                    real[i + k] = evenReal + oddReal
                    imag[i + k] = evenImag + oddImag
                    real[i + k + len / 2] = evenReal - oddReal
                    imag[i + k + len / 2] = evenImag - oddImag
                    val nextReal = curReal * wReal - curImag * wImag
                    val nextImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                    curImag = nextImag
                }
                i += len
            }
            len = len shl 1
        }
    }
}
