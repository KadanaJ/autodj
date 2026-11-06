package com.autodj.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pitch-preserving time stretcher (WSOLA: waveform-similarity overlap-add) for stereo audio.
 *
 * Each output hop takes a window of input from near the "nominal" read position, shifted by up
 * to ±[tolerance] samples to the spot whose waveform best continues the previous window, then
 * overlap-adds it. At rate 1.0 it reproduces the input exactly.
 *
 * [rate] > 1 plays faster (higher BPM), < 1 slower. It can change at any time.
 */
class Wsola(sampleRate: Int, private val source: PcmSource) {
    val frameSize: Int = if (sampleRate > 30000) 1024 else 512
    val hop: Int = frameSize / 2
    private val tolerance = frameSize / 4
    private val window = FloatArray(frameSize) { (0.5 - 0.5 * cos(2 * PI * it / frameSize)).toFloat() }

    // Input history (stereo, interleaved). inBase = absolute frame index of inBuf frame 0.
    private var inBuf = FloatArray(frameSize * 16)
    private var inBase = 0L
    private var inFrames = 0
    private var sourceEnded = false

    private var nominal = 0.0
    private var prevPos = -1L
    private val acc = FloatArray(frameSize * 2)

    var rate = 1.0

    /** Absolute source frame of the next window to be read (tracks the song's playback position). */
    val position: Double get() = nominal

    /** True once the source is exhausted and everything has been played. */
    val finished: Boolean get() = sourceEnded && nominal.roundToLong() >= inBase + inFrames

    /** Starts reading at absolute source frame [frame] (forward only from where the source is). */
    fun start(frame: Long) {
        source.skip(frame)
        inBase = frame
        inFrames = 0
        nominal = frame.toDouble()
        prevPos = -1
        acc.fill(0f)
    }

    /** Writes exactly [hop] stereo frames to [out] at frame offset [outOffset]. */
    fun produce(out: FloatArray, outOffset: Int) {
        val a = nominal.roundToLong()
        val p: Long
        if (prevPos < 0) {
            ensure(a, a + frameSize)
            p = a
        } else {
            val tStart = prevPos + hop
            ensure(minOf(tStart, a - tolerance), maxOf(tStart + frameSize / 2, a + tolerance + frameSize))
            p = a + bestOffset(tStart, a)
        }
        // Overlap-add the chosen window.
        val base = ((p - inBase) * 2).toInt()
        for (k in 0 until frameSize) {
            val w = window[k]
            val i = base + 2 * k
            val l = if (i >= 0 && i + 1 < inFrames * 2) inBuf[i] else 0f
            val r = if (i >= 0 && i + 1 < inFrames * 2) inBuf[i + 1] else 0f
            acc[2 * k] += w * l
            acc[2 * k + 1] += w * r
        }
        System.arraycopy(acc, 0, out, outOffset * 2, hop * 2)
        System.arraycopy(acc, hop * 2, acc, 0, (frameSize - hop) * 2)
        acc.fill(0f, (frameSize - hop) * 2, frameSize * 2)
        prevPos = p
        nominal += hop * rate
        discardBefore(minOf(prevPos + hop, nominal.roundToLong() - tolerance) - 16)
    }

    /** Offset in [-tolerance, tolerance] whose window best continues the previous one. */
    private fun bestOffset(templateStart: Long, a: Long): Int {
        val len = frameSize / 2
        val tBase = ((templateStart - inBase) * 2).toInt()
        // Coarse search on 2x-decimated mono, then refine at full resolution.
        // Scan outward from 0 so ties (periodic audio) keep the unshifted position.
        var best = 0
        var bestScore = score(tBase, ((a - inBase) * 2).toInt(), len, 4)
        var d = 2
        while (d <= tolerance) {
            for (cand in intArrayOf(-d, d)) {
                val s = score(tBase, ((a + cand - inBase) * 2).toInt(), len, 4)
                if (s > bestScore + 1e-9 * abs(bestScore) + 1e-12) {
                    bestScore = s
                    best = cand
                }
            }
            d += 2
        }
        val coarse = best
        var fineScore = score(tBase, ((a + coarse - inBase) * 2).toInt(), len, 2)
        for (fd in coarse - 1..coarse + 1) {
            if (fd < -tolerance || fd > tolerance || fd == coarse) continue
            val s = score(tBase, ((a + fd - inBase) * 2).toInt(), len, 2)
            if (s > fineScore + 1e-9 * abs(fineScore) + 1e-12) {
                fineScore = s
                best = fd
            }
        }
        return best
    }

    /** Normalised cross-correlation of mono-summed windows, sampling every [stride]/2 frames. */
    private fun score(tBase: Int, cBase: Int, len: Int, stride: Int): Double {
        var dot = 0.0
        var cc = 0.0
        val max = inFrames * 2
        var k = 0
        while (k < len * 2) {
            val ti = tBase + k
            val ci = cBase + k
            if (ti < 0 || ci < 0 || ti + 1 >= max || ci + 1 >= max) {
                k += stride
                continue
            }
            val t = inBuf[ti] + inBuf[ti + 1]
            val c = inBuf[ci] + inBuf[ci + 1]
            dot += t * c
            cc += c * c
            k += stride
        }
        return if (cc > 1e-12) dot / sqrt(cc) else if (dot == 0.0) 0.0 else Double.NEGATIVE_INFINITY
    }

    /** Makes sure input frames [from, to) are buffered (zeros past the end of the song). */
    private fun ensure(from: Long, to: Long) {
        if (from < inBase) return // cannot go back; treated as silence
        val need = (to - inBase).toInt()
        if (need * 2 > inBuf.size) inBuf = inBuf.copyOf(maxOf(need * 2, inBuf.size * 2))
        while (inFrames < need) {
            if (sourceEnded) {
                // pad with silence
                inBuf.fill(0f, inFrames * 2, need * 2)
                return
            }
            val n = source.read(inBuf, inFrames, need - inFrames)
            when {
                n < 0 -> sourceEnded = true
                n == 0 -> {
                    // Decoder not ready: play silence rather than block the audio thread.
                    inBuf.fill(0f, inFrames * 2, need * 2)
                    underruns++
                    return
                }
                else -> inFrames += n
            }
        }
    }

    var underruns = 0
        private set

    private fun discardBefore(frame: Long) {
        val drop = (frame - inBase).toInt()
        if (drop <= 0) return
        val d = minOf(drop, inFrames)
        System.arraycopy(inBuf, d * 2, inBuf, 0, (inFrames - d) * 2)
        inFrames -= d
        inBase += d
    }
}
