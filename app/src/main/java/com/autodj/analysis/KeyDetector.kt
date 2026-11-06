package com.autodj.analysis

import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Musical key via a long-window chromagram matched against Krumhansl–Kessler key profiles.
 * Key index: 0..11 = C..B major, 12..23 = C..B minor.
 */
object KeyDetector {
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    data class Result(val keyIndex: Int, val confidence: Double)

    fun detect(x: FloatArray, sampleRate: Int): Result? {
        val chroma = chromagram(x, sampleRate) ?: return null
        val scores = DoubleArray(24) { k ->
            val profile = if (k < 12) MAJOR else MINOR
            val tonic = k % 12
            correlation(chroma) { pc -> profile[(pc - tonic + 12) % 12] }
        }
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order[0]
        val margin = scores[best] - scores[order[1]]
        val confidence = (scores[best].coerceAtLeast(0.0) * 0.5 + margin * 4).coerceIn(0.0, 1.0)
        return Result(best, confidence)
    }

    internal fun chromagram(x: FloatArray, sampleRate: Int): DoubleArray? {
        val n = 4096
        val hop = 2048
        if (x.size < n * 4) return null
        val fft = Fft(n)
        val win = Fft.hann(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val bins = n / 2
        val pcOfBin = IntArray(bins) { k ->
            val f = k.toDouble() * sampleRate / n
            if (f < 65 || f > 2100) -1 else ((69 + 12 * log2(f / 440.0)).roundToInt() % 12 + 12) % 12
        }
        val total = DoubleArray(12)
        val frame = DoubleArray(12)
        var t = 0
        while (t + n <= x.size) {
            for (i in 0 until n) {
                re[i] = x[t + i] * win[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            frame.fill(0.0)
            for (k in 1 until bins) {
                val pc = pcOfBin[k]
                if (pc < 0) continue
                val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
                frame[pc] += ln(1 + mag)
            }
            val max = frame.max()
            if (max > 1e-6) for (i in 0 until 12) total[i] += frame[i] / max
            t += hop
        }
        return if (total.sum() > 0) total else null
    }

    private inline fun correlation(a: DoubleArray, b: (Int) -> Double): Double {
        val ma = a.average()
        var mb = 0.0
        for (i in 0 until 12) mb += b(i)
        mb /= 12
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until 12) {
            val x = a[i] - ma
            val y = b(i) - mb
            num += x * y
            da += x * x
            db += y * y
        }
        return if (da > 0 && db > 0) num / sqrt(da * db) else 0.0
    }

    fun name(keyIndex: Int): String =
        NAMES[keyIndex % 12] + if (keyIndex >= 12) "m" else ""

    /** Camelot wheel code, e.g. 8A = A minor, 8B = C major. */
    fun camelot(keyIndex: Int): String {
        val minor = keyIndex >= 12
        val pc = keyIndex % 12
        val majorPc = if (minor) (pc + 3) % 12 else pc
        val number = (majorPc * 7 % 12 + 7) % 12 + 1
        return "$number${if (minor) "A" else "B"}"
    }
}
