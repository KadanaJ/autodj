package com.autodj.analysis

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class TrackAnalysis(
    val durationSec: Double,
    val grid: BeatGrid?,
    val keyIndex: Int?,
    val keyConfidence: Double,
    /** 0..1 overall intensity. */
    val energy: Double,
    val loudnessDb: Double,
    /** Detected onsets per second. */
    val onsetRate: Double,
    /** Mean share of energy above 2 kHz. */
    val brightness: Double,
    /**
     * Per-beat loudness in dB (full band, then low band, interleaved), used later to find
     * intros, outros and phrase boundaries for mixing.
     */
    val beatEnergy: FloatArray,
) {
    companion object {
        /** Bump when analysis changes so stored results get recomputed. */
        const val VERSION = 1
    }
}

object TrackAnalyzer {
    /**
     * @param x mono samples in -1..1
     * @param bpmHint optional user-tapped tempo used to pick the right tempo family
     */
    fun analyze(x: FloatArray, sampleRate: Int, bpmHint: Double? = null): TrackAnalysis {
        val duration = x.size.toDouble() / sampleRate
        val f = SpectralFeatures.compute(x, sampleRate)
        val grid = TempoEstimator.estimate(f, bpmHint)
        val key = KeyDetector.detect(x, sampleRate)

        // Ignore the first and last 10 %, where intros and fade-outs live.
        val a = (f.frames * 0.1).toInt()
        val b = max(a + 1, (f.frames * 0.9).toInt())
        var e = 0.0
        var bw = 0.0
        var bsum = 0.0
        for (t in a until b) {
            val fe = f.frameEnergy[t].toDouble()
            e += fe
            bw += f.brightness[t] * fe
            bsum += fe
        }
        val rms = sqrt(e / (b - a))
        val loudnessDb = 20 * log10(max(rms, 1e-6))
        val brightness = if (bsum > 0) bw / bsum else 0.0
        val onsetRate = onsetRate(f.novelty, f.frameRate, a, b)

        val energy = (
            0.40 * lin(loudnessDb, -24.0, -8.0) +
                0.35 * lin(onsetRate, 1.0, 6.0) +
                0.25 * lin(brightness, 0.02, 0.22)
            ).coerceIn(0.0, 1.0)

        return TrackAnalysis(
            durationSec = duration,
            grid = grid,
            keyIndex = key?.keyIndex,
            keyConfidence = key?.confidence ?: 0.0,
            energy = energy,
            loudnessDb = loudnessDb,
            onsetRate = onsetRate,
            brightness = brightness,
            beatEnergy = beatEnergy(f, grid, duration),
        )
    }

    private fun lin(v: Double, lo: Double, hi: Double) = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

    private fun onsetRate(nov: FloatArray, frameRate: Double, a: Int, b: Int): Double {
        if (b - a < 3) return 0.0
        var mean = 0.0
        for (t in a until b) mean += nov[t]
        mean /= (b - a)
        var sd = 0.0
        for (t in a until b) sd += (nov[t] - mean) * (nov[t] - mean)
        sd = sqrt(sd / (b - a))
        val threshold = mean + 0.5 * sd
        val minGap = (0.06 * frameRate).toInt()
        var count = 0
        var last = -minGap
        for (t in a + 1 until b - 1) {
            if (nov[t] > threshold && nov[t] >= nov[t - 1] && nov[t] > nov[t + 1] && t - last >= minGap) {
                count++
                last = t
            }
        }
        return count / ((b - a) / frameRate)
    }

    private fun beatEnergy(f: SpectralFeatures, grid: BeatGrid?, duration: Double): FloatArray {
        // Without a usable grid, fall back to half-second blocks.
        val step = grid?.beatLength ?: 0.5
        val start = grid?.firstBeat ?: 0.0
        val count = max(0, ((duration - start) / step).toInt())
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val t0 = start + i * step
            val f0 = max(0, f.timeToFrame(t0).toInt())
            val f1 = minOf(f.frames, f.timeToFrame(t0 + step).toInt())
            var e = 0.0
            var le = 0.0
            val n = max(1, f1 - f0)
            for (k in f0 until f1) {
                e += f.frameEnergy[k]
                le += f.lowEnergy[k]
            }
            out[2 * i] = (10 * log10(max(e / n, 1e-10))).toFloat()
            out[2 * i + 1] = (10 * log10(max(le / n, 1e-10))).toFloat()
        }
        return out
    }
}
