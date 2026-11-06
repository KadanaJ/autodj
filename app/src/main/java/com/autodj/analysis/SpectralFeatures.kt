package com.autodj.analysis

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Frame-level features computed in one STFT pass over mono audio.
 * Frame t covers samples [t*hop, t*hop + n); its time is the window centre.
 */
class SpectralFeatures(
    val sampleRate: Int,
    val hop: Int,
    val frameSize: Int,
    /** Onset novelty (spectral flux, local-mean removed, rectified). */
    val novelty: FloatArray,
    /** Low-frequency (< ~180 Hz) flux, i.e. kick / bass onsets. */
    val lowNovelty: FloatArray,
    /** Mean-square energy of each frame. */
    val frameEnergy: FloatArray,
    /** Energy below ~180 Hz. */
    val lowEnergy: FloatArray,
    /** Fraction of spectral energy above 2 kHz. */
    val brightness: FloatArray,
) {
    val frameRate: Double get() = sampleRate.toDouble() / hop
    val frames: Int get() = novelty.size

    /**
     * Time in seconds of the onset that peaks at frame index (may be fractional). Spectral flux
     * peaks when an attack sits about 3/4 into the Hann window, where the window rises fastest.
     */
    fun frameTime(frame: Double): Double = (frame * hop + latencySamples) / sampleRate

    fun timeToFrame(seconds: Double): Double = (seconds * sampleRate - latencySamples) / hop

    private val latencySamples: Double get() = 0.75 * frameSize + 0.25 * hop

    companion object {
        const val FRAME = 1024
        const val HOP = 128

        fun compute(x: FloatArray, sampleRate: Int): SpectralFeatures {
            val n = FRAME
            val hop = HOP
            val frames = if (x.size < n) 0 else 1 + (x.size - n) / hop
            val fft = Fft(n)
            val win = Fft.hann(n)
            val bins = n / 2 + 1
            val lowBins = max(2, (180.0 * n / sampleRate).toInt())
            val brightBin = (2000.0 * n / sampleRate).toInt().coerceIn(1, bins - 1)
            val re = FloatArray(n)
            val im = FloatArray(n)
            val prev = FloatArray(bins)
            val cur = FloatArray(bins)
            val flux = FloatArray(frames)
            val lowFlux = FloatArray(frames)
            val energy = FloatArray(frames)
            val lowEnergy = FloatArray(frames)
            val bright = FloatArray(frames)

            for (t in 0 until frames) {
                val off = t * hop
                var e = 0.0
                for (i in 0 until n) {
                    val s = x[off + i]
                    e += s * s
                    re[i] = s * win[i]
                    im[i] = 0f
                }
                energy[t] = (e / n).toFloat()
                fft.transform(re, im)
                var f = 0.0
                var lf = 0.0
                var total = 0.0
                var high = 0.0
                var low = 0.0
                for (k in 0 until bins) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    total += p
                    if (k >= brightBin) high += p
                    if (k in 1..lowBins) low += p
                    val l = ln(1f + 10f * sqrt(p))
                    cur[k] = l
                    if (t > 0) {
                        val d = l - prev[k]
                        if (d > 0) {
                            f += d
                            if (k in 1..lowBins) lf += d
                        }
                    }
                }
                System.arraycopy(cur, 0, prev, 0, bins)
                flux[t] = (f / bins).toFloat()
                lowFlux[t] = (lf / lowBins).toFloat()
                lowEnergy[t] = (low / (n * n / 4.0)).toFloat()
                bright[t] = if (total > 1e-12) (high / total).toFloat() else 0f
            }

            val frameRate = sampleRate.toDouble() / hop
            val window = (0.3 * frameRate).toInt()
            val novelty = removeLocalMean(flux, window)
            val lowNovelty = removeLocalMean(lowFlux, window)
            // Combine broadband and kick onsets so both drums and tonal attacks count.
            val combined = FloatArray(frames) { novelty[it] + 0.5f * lowNovelty[it] }
            return SpectralFeatures(sampleRate, hop, n, combined, lowNovelty, energy, lowEnergy, bright)
        }

        /** Subtracts a centred moving average and half-wave rectifies. */
        fun removeLocalMean(x: FloatArray, halfWidth: Int): FloatArray {
            val n = x.size
            val prefix = DoubleArray(n + 1)
            for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
            return FloatArray(n) { i ->
                val a = max(0, i - halfWidth)
                val b = minOf(n, i + halfWidth + 1)
                val mean = (prefix[b] - prefix[a]) / (b - a)
                max(0.0, x[i] - mean).toFloat()
            }
        }
    }
}
