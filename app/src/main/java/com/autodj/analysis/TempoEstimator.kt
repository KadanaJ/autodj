package com.autodj.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A constant-tempo beat grid: beat i is at firstBeat + i * 60 / bpm seconds. */
data class BeatGrid(
    val bpm: Double,
    /** Time of the first beat at or after 0 s. */
    val firstBeat: Double,
    /** 0..1, how well a constant grid explains the onsets. */
    val confidence: Double,
    /** Which beat (0..3) of the grid starts a bar. */
    val downbeatOffset: Int,
) {
    val beatLength: Double get() = 60.0 / bpm
    fun beatTime(i: Int): Double = firstBeat + i * beatLength
}

object TempoEstimator {
    const val MIN_BPM = 60.0
    const val MAX_BPM = 190.0
    private const val PRIOR_CENTER = 118.0
    private const val PRIOR_OCTAVE_SIGMA = 0.85

    /**
     * @param bpmHint a rough tempo from the user (tap tempo). When given, the grid is fitted
     * around it and the automatic octave choice is skipped.
     */
    fun estimate(f: SpectralFeatures, bpmHint: Double? = null): BeatGrid? {
        val nov = f.novelty
        if (nov.size < f.frameRate * 10) return null
        val coarse = bpmHint ?: coarseTempo(nov, f.frameRate) ?: return null
        var bpm = refineTempo(nov, f.frameRate, coarse)
        if (bpmHint == null && bpm < 100 && bpm * 2 <= MAX_BPM && offbeatRatio(nov, f.frameRate, bpm) > 0.6) {
            // Strong hits halfway between beats: this is double-time music (e.g. DnB at 174, not 87).
            bpm = refineTempo(nov, f.frameRate, bpm * 2)
        }
        val (phaseFrames, sharpness) = phase(nov, f.frameRate, bpm, bins = 240)
        val period = 60.0 * f.frameRate / bpm
        var firstBeat = f.frameTime(phaseFrames)
        while (firstBeat - 60.0 / bpm >= 0) firstBeat -= 60.0 / bpm
        while (firstBeat < 0) firstBeat += 60.0 / bpm
        val stability = stability(nov, f.frameRate, bpm)
        // sharpness ~1.5 for noise-like input, 5+ for a solid four-on-the-floor grid.
        val sharpScore = ((sharpness - 1.6) / 3.0).coerceIn(0.0, 1.0)
        val coverage = coverage(f, bpm, firstBeat)
        val coverageScore = ((coverage - 0.45) / 0.3).coerceIn(0.0, 1.0)
        val confidence = (sharpScore * stability * coverageScore).coerceIn(0.0, 1.0)
        val downbeat = downbeat(f, bpm, firstBeat)
        check(period > 0)
        return BeatGrid(bpm, firstBeat, confidence, downbeat)
    }

    /** Autocorrelation with harmonic comb and a log-normal tempo prior. */
    internal fun coarseTempo(nov: FloatArray, frameRate: Double): Double? {
        val n = nov.size
        val size = Fft.nextPow2(2 * n)
        val re = FloatArray(size)
        val im = FloatArray(size)
        val mean = nov.average().toFloat()
        for (i in 0 until n) re[i] = nov[i] - mean
        val fft = Fft(size)
        fft.transform(re, im)
        for (i in 0 until size) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0f
        }
        // Inverse via forward transform of the (real, symmetric) power spectrum.
        fft.transform(re, im)
        val acf = DoubleArray(size / 2) { re[it].toDouble() / (n - minOf(it, n - 1)) }
        if (acf[0] <= 0) return null

        fun acfAt(lag: Double): Double {
            val i = floor(lag).toInt()
            if (i < 0 || i + 1 >= acf.size) return 0.0
            val fr = lag - i
            return acf[i] * (1 - fr) + acf[i + 1] * fr
        }

        val minLag = 60.0 * frameRate / MAX_BPM
        val maxLag = 60.0 * frameRate / MIN_BPM
        var bestLag = -1.0
        var bestScore = Double.NEGATIVE_INFINITY
        var lag = minLag
        while (lag <= maxLag) {
            val comb = acfAt(lag) + 0.5 * acfAt(2 * lag) + 0.25 * acfAt(3 * lag) + 0.25 * acfAt(4 * lag)
            val bpm = 60.0 * frameRate / lag
            val oct = ln(bpm / PRIOR_CENTER) / ln(2.0)
            val w = exp(-0.5 * (oct / PRIOR_OCTAVE_SIGMA) * (oct / PRIOR_OCTAVE_SIGMA))
            val score = comb * w
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
            lag += 0.25
        }
        if (bestLag <= 0 || bestScore <= 0) return null
        return 60.0 * frameRate / bestLag
    }

    /** Finds the BPM whose grid folds the onsets into the sharpest peak across the whole track. */
    internal fun refineTempo(nov: FloatArray, frameRate: Double, coarse: Double): Double {
        var best = coarse
        var bestScore = -1.0
        fun scan(from: Double, to: Double, step: Double) {
            var b = from
            while (b <= to) {
                val s = foldPeak(nov, frameRate, b, 64)
                if (s > bestScore) {
                    bestScore = s
                    best = b
                }
                b += step
            }
        }
        scan(coarse * 0.97, coarse * 1.03, 0.05)
        val c = best
        scan(c - 0.08, c + 0.08, 0.005)
        // Producers usually pick whole (or half) BPMs; snap when it costs almost nothing.
        for (snap in listOf(best.roundToInt().toDouble(), (best * 2).roundToInt() / 2.0)) {
            if (abs(snap - best) < 0.06) {
                val s = foldPeak(nov, frameRate, snap, 64)
                if (s >= bestScore * 0.985) return snap
            }
        }
        return best
    }

    /** Onset strength halfway between beats relative to on the beat. */
    internal fun offbeatRatio(nov: FloatArray, frameRate: Double, bpm: Double): Double {
        val bins = 64
        val h = smooth(fold(nov, frameRate, bpm, bins), 1)
        var bi = 0
        for (i in h.indices) if (h[i] > h[bi]) bi = i
        val mean = h.average()
        var off = 0.0
        for (d in -2..2) off = maxOf(off, h[(bi + bins / 2 + d + bins) % bins])
        val on = h[bi] - mean
        return if (on > 0) (off - mean) / on else 0.0
    }

    private fun fold(nov: FloatArray, frameRate: Double, bpm: Double, bins: Int, from: Int = 0, to: Int = nov.size): DoubleArray {
        val period = 60.0 * frameRate / bpm
        val hist = DoubleArray(bins)
        val inv = 1.0 / period
        for (t in from until to) {
            val v = nov[t]
            if (v == 0f) continue
            val ph = t * inv
            val frac = ph - floor(ph)
            // Split each frame linearly between two bins for sub-bin accuracy.
            val pos = frac * bins
            val i0 = floor(pos).toInt() % bins
            val w = pos - floor(pos)
            hist[i0] += v * (1 - w)
            hist[(i0 + 1) % bins] += v * w
        }
        return hist
    }

    private fun smooth(h: DoubleArray, radius: Int): DoubleArray {
        val n = h.size
        return DoubleArray(n) { i ->
            var s = 0.0
            var wsum = 0.0
            for (d in -radius..radius) {
                val w = (radius + 1 - abs(d)).toDouble()
                s += h[((i + d) % n + n) % n] * w
                wsum += w
            }
            s / wsum
        }
    }

    private fun foldPeak(nov: FloatArray, frameRate: Double, bpm: Double, bins: Int): Double {
        val h = smooth(fold(nov, frameRate, bpm, bins), 1)
        val mean = h.average()
        if (mean <= 0) return 0.0
        return h.max() / mean
    }

    /** Returns (phase of the beat in frames within one period, peak sharpness). */
    internal fun phase(nov: FloatArray, frameRate: Double, bpm: Double, bins: Int, from: Int = 0, to: Int = nov.size): Pair<Double, Double> {
        val h = smooth(fold(nov, frameRate, bpm, bins, from, to), bins / 64)
        var bi = 0
        for (i in h.indices) if (h[i] > h[bi]) bi = i
        val l = h[(bi - 1 + bins) % bins]
        val c = h[bi]
        val r = h[(bi + 1) % bins]
        val denom = l - 2 * c + r
        val delta = if (denom != 0.0) (0.5 * (l - r) / denom).coerceIn(-0.5, 0.5) else 0.0
        val frac = ((bi + delta) / bins + 1.0) % 1.0
        val period = 60.0 * frameRate / bpm
        val mean = h.average()
        return Pair(frac * period, if (mean > 0) c / mean else 0.0)
    }

    /** Compares beat phase across quarters of the track: 1 = steady tempo, 0 = drifting. */
    private fun stability(nov: FloatArray, frameRate: Double, bpm: Double): Double {
        val period = 60.0 * frameRate / bpm
        val parts = 4
        val len = nov.size / parts
        val phases = (0 until parts).map { p ->
            phase(nov, frameRate, bpm, 120, p * len, (p + 1) * len).first / period
        }
        var sx = 0.0
        var sy = 0.0
        for (ph in phases) {
            sx += kotlin.math.cos(2 * Math.PI * ph)
            sy += kotlin.math.sin(2 * Math.PI * ph)
        }
        // Mean resultant length of the phases on the circle.
        val r = sqrt(sx * sx + sy * sy) / parts
        return ((r - 0.6) / 0.35).coerceIn(0.0, 1.0)
    }

    /**
     * Fraction of grid beats (middle 80 % of the track) whose surrounding beat-length window has
     * its strongest onset right on the beat. Random onsets score ~0.15, a real groove 0.7+.
     */
    internal fun coverage(f: SpectralFeatures, bpm: Double, firstBeat: Double): Double {
        val nov = f.novelty
        val beatLen = 60.0 / bpm
        val half = (beatLen * f.frameRate / 2).toInt()
        val duration = f.frameTime(f.frames.toDouble())
        var hits = 0
        var total = 0
        var i = 0
        while (true) {
            val t = firstBeat + i * beatLen
            if (t > duration * 0.9) break
            if (t >= duration * 0.1) {
                val fr = f.timeToFrame(t).roundToInt()
                var near = 0f
                var window = 0f
                for (k in maxOf(0, fr - half) until minOf(f.frames, fr + half)) {
                    window = maxOf(window, nov[k])
                    if (abs(k - fr) <= 2) near = maxOf(near, nov[k])
                }
                total++
                if (window > 0 && near >= 0.8f * window) hits++
            }
            i++
        }
        return if (total > 0) hits.toDouble() / total else 0.0
    }

    /** Chooses the beat (mod 4) with the strongest kick/bass onsets as the bar start. */
    private fun downbeat(f: SpectralFeatures, bpm: Double, firstBeat: Double): Int {
        val sums = DoubleArray(4)
        val beatLen = 60.0 / bpm
        var i = 0
        while (true) {
            val t = firstBeat + i * beatLen
            val fr = f.timeToFrame(t).roundToInt()
            if (fr >= f.frames) break
            if (fr >= 0) {
                var m = 0f
                for (d in -2..2) {
                    val k = fr + d
                    if (k in 0 until f.frames) m = maxOf(m, f.lowNovelty[k])
                }
                // Bar starts tend to carry a stronger low end.
                sums[i % 4] += m.toDouble()
            }
            i++
        }
        var best = 0
        for (k in 1..3) if (sums[k] > sums[best]) best = k
        return best
    }
}
