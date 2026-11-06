package com.autodj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. Not thread-safe: use one instance per thread. */
class Fft(val size: Int) {
    private val levels: Int
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val bitRev = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        levels = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2 * PI * i / size).toFloat()
            sinTable[i] = sin(2 * PI * i / size).toFloat()
        }
        for (i in 0 until size) {
            bitRev[i] = Integer.reverse(i) ushr (32 - levels)
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
    }

    companion object {
        fun nextPow2(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        fun hann(n: Int): FloatArray =
            FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    }
}
