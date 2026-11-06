package com.autodj.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

enum class TransitionType { BEATMATCH, CROSSFADE }

data class TransitionPlan(
    val type: TransitionType,
    /** Song time in A where the blend starts. */
    val aStart: Double,
    /** Song time in B where B starts playing. */
    val bStart: Double,
    /** Blend length in A beats (BEATMATCH) — 0 for crossfades. */
    val beats: Int,
    /** Blend length in seconds of A song time. */
    val lengthSec: Double,
    /** How many of B's beats pass per A beat (1, or 2 / 0.5 for half/double time). */
    val beatRatio: Double,
    /** B's playback rate needed so its beats match A's, relative to A's own rate. */
    val bRateRelative: Double,
)

object TransitionPlanner {
    const val MAX_STRETCH = 0.06
    const val BLEND_BEATS = 32
    const val SHORT_BLEND_BEATS = 16
    const val CROSSFADE_SEC = 8.0

    /**
     * @param earliest song time in A before which the blend may not start (current position + lead).
     * @param quick use a short blend as soon as possible (the user pressed skip).
     */
    fun plan(a: TrackInfo, b: TrackInfo, earliest: Double, quick: Boolean = false): TransitionPlan {
        val match = beatRatio(a, b)
        if (match != null) {
            beatmatch(a, b, earliest, quick, match.first, match.second)?.let { return it }
        }
        return crossfade(a, b, earliest, quick)
    }

    /** (B beats per A beat, B rate relative to A) if the tempos can be matched within ±6 %. */
    fun beatRatio(a: TrackInfo, b: TrackInfo): Pair<Double, Double>? {
        if (!a.beatable || !b.beatable) return null
        val ba = a.bpm!!
        val bb = b.bpm!!
        for (ratio in doubleArrayOf(1.0, 2.0, 0.5)) {
            // B played so that `ratio` B beats fit one A beat.
            val rate = ba * ratio / bb
            if (abs(rate - 1) <= MAX_STRETCH) return ratio to rate
        }
        return null
    }

    private fun beatmatch(
        a: TrackInfo,
        b: TrackInfo,
        earliest: Double,
        quick: Boolean,
        ratio: Double,
        rate: Double,
        asap: Boolean = quick,
    ): TransitionPlan? {
        val aLen = a.beatLength
        val aFirst = a.firstBeat!!
        val aEnd = lastLoudTime(a)
        val beats = if (quick) SHORT_BLEND_BEATS else BLEND_BEATS
        val earliestBeat = ceil((earliest - aFirst) / aLen).toInt().coerceAtLeast(0)
        val phrase = if (quick) 4 else 16
        val lastStart = floor((aEnd - aFirst) / aLen).toInt() - beats

        // Normally the latest phrase boundary that still fits before A's music runs out;
        // on skip, the earliest bar line.
        fun onGrid(k: Int) = ((k - a.downbeatOffset) % phrase + phrase) % phrase == 0
        var start = -1
        if (asap) {
            start = (earliestBeat..lastStart).firstOrNull { onGrid(it) } ?: -1
        } else {
            var k = lastStart
            while (k >= earliestBeat) {
                if (onGrid(k)) {
                    start = k
                    break
                }
                k--
            }
        }
        if (start < 0 && !quick) {
            // Not enough room on a 16-beat phrase: try bar boundaries with a shorter blend.
            return beatmatch(a, b, earliest, true, ratio, rate, asap = false)
        }
        if (start < 0) return null

        // B enters on its first bar line once its music starts.
        // One A beat spans `ratio` B beats of B's song time.
        val bLenA = b.beatLength * ratio
        val bFirst = b.firstBeat!!
        val bLoud = firstLoudTime(b)
        var bBeat = ceil((bLoud - bFirst) / b.beatLength - 1e-6).toInt().coerceAtLeast(0)
        while (((bBeat - b.downbeatOffset) % 4 + 4) % 4 != 0) bBeat++
        val bStart = bFirst + bBeat * b.beatLength
        // B must have enough song left for the whole blend.
        if (bStart + beats * bLenA > b.durationSec - 5) return null

        return TransitionPlan(
            type = TransitionType.BEATMATCH,
            aStart = aFirst + start * aLen,
            bStart = bStart,
            beats = beats,
            lengthSec = beats * aLen,
            beatRatio = ratio,
            bRateRelative = rate,
        )
    }

    private fun crossfade(a: TrackInfo, b: TrackInfo, earliest: Double, quick: Boolean): TransitionPlan {
        val len = if (quick) 5.0 else CROSSFADE_SEC
        val aEnd = lastLoudTime(a)
        val start = if (quick) earliest else max(earliest, aEnd - len).coerceAtMost(max(earliest, a.durationSec - len))
        return TransitionPlan(
            type = TransitionType.CROSSFADE,
            aStart = start,
            bStart = firstLoudTime(b),
            beats = 0,
            lengthSec = len,
            beatRatio = 1.0,
            bRateRelative = 1.0,
        )
    }

    /** Level (dB) that counts as "the music is playing": 15 dB under the loud parts. */
    private fun threshold(t: TrackInfo): Float {
        val e = FloatArray(t.blocks) { t.blockEnergy[2 * it] }
        if (e.isEmpty()) return -200f
        val sorted = e.sorted()
        return sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)] - 15f
    }

    /** Time the music fades away (end of the last block above threshold). */
    fun lastLoudTime(t: TrackInfo): Double {
        if (t.blocks == 0) return t.durationSec
        val th = threshold(t)
        for (i in t.blocks - 1 downTo 0) {
            if (t.blockEnergy[2 * i] > th) return minOf(t.durationSec, t.blockStart + (i + 1) * t.blockLength)
        }
        return t.durationSec
    }

    /** Time the music actually starts (skipping leading silence). */
    fun firstLoudTime(t: TrackInfo): Double {
        if (t.blocks == 0) return 0.0
        val th = threshold(t)
        for (i in 0 until t.blocks) {
            if (t.blockEnergy[2 * i] > th) return max(0.0, t.blockStart + i * t.blockLength)
        }
        return 0.0
    }
}
