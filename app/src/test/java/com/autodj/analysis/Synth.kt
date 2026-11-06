package com.autodj.analysis

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Tiny drum-machine for generating test tracks with known tempo, phase and key. */
class Synth(val sampleRate: Int, seconds: Double, seed: Int = 1) {
    val out = FloatArray((seconds * sampleRate).toInt())
    private val rnd = Random(seed)

    private fun idx(t: Double) = (t * sampleRate).toInt()

    fun kick(t: Double, gain: Double = 0.9) {
        val s = idx(t)
        var ph = 0.0
        for (i in 0 until (0.25 * sampleRate).toInt()) {
            val k = s + i
            if (k !in out.indices) break
            val tt = i.toDouble() / sampleRate
            val f = 50 + 110 * exp(-tt * 30)
            ph += 2 * PI * f / sampleRate
            out[k] += (gain * sin(ph) * exp(-tt * 12)).toFloat()
        }
    }

    fun noiseHit(t: Double, len: Double, gain: Double, decay: Double) {
        val s = idx(t)
        var prev = 0.0
        for (i in 0 until (len * sampleRate).toInt()) {
            val k = s + i
            if (k !in out.indices) break
            val n = rnd.nextDouble(-1.0, 1.0)
            val hp = n - prev // crude high-pass
            prev = n
            out[k] += (gain * hp * exp(-i.toDouble() / sampleRate * decay)).toFloat()
        }
    }

    fun hat(t: Double, gain: Double = 0.15) = noiseHit(t, 0.04, gain, 90.0)
    fun snare(t: Double, gain: Double = 0.35) {
        noiseHit(t, 0.15, gain, 25.0)
        tone(t, 0.1, 190.0, gain * 0.6, 30.0)
    }

    fun tone(t: Double, len: Double, freq: Double, gain: Double, decay: Double = 0.0) {
        val s = idx(t)
        for (i in 0 until (len * sampleRate).toInt()) {
            val k = s + i
            if (k !in out.indices) break
            val tt = i.toDouble() / sampleRate
            val env = minOf(1.0, tt * 200) * exp(-tt * decay)
            out[k] += (gain * env * sin(2 * PI * freq * tt)).toFloat()
        }
    }

    /** Sustained chord with a few harmonics, MIDI note numbers. */
    fun chord(t: Double, len: Double, notes: IntArray, gain: Double = 0.06) {
        for (n in notes) {
            val f = 440.0 * 2.0.pow((n - 69) / 12.0)
            tone(t, len, f, gain)
            tone(t, len, 2 * f, gain * 0.4)
            tone(t, len, 3 * f, gain * 0.2)
        }
    }

    fun noise(gain: Double) {
        for (i in out.indices) out[i] += (gain * rnd.nextDouble(-1.0, 1.0)).toFloat()
    }

    companion object {
        /** A-minor loop Am – F – C – G, one chord per bar. */
        val A_MINOR_PROGRESSION = arrayOf(
            intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(48, 52, 55), intArrayOf(55, 59, 62),
        )
        val A_MINOR_BASS = intArrayOf(45, 41, 48, 43)

        fun house(bpm: Double, offset: Double, seconds: Double = 120.0, sr: Int = 11025): Synth {
            val s = Synth(sr, seconds)
            val beat = 60.0 / bpm
            var i = 0
            while (offset + i * beat < seconds) {
                val t = offset + i * beat
                s.kick(t)
                s.hat(t + beat / 2)
                if (i % 2 == 1) s.snare(t, 0.25)
                if (i % 4 == 0) {
                    val bar = (i / 4) % 4
                    s.chord(t, beat * 4, A_MINOR_PROGRESSION[bar])
                    val bassF = 440.0 * 2.0.pow((A_MINOR_BASS[bar] - 69) / 12.0)
                    for (q in 0 until 4) s.tone(t + q * beat + beat / 2, beat * 0.4, bassF, 0.12, 6.0)
                }
                i++
            }
            s.noise(0.003)
            return s
        }

        fun hipHop(bpm: Double, offset: Double, seconds: Double = 120.0, sr: Int = 11025): Synth {
            val s = Synth(sr, seconds)
            val beat = 60.0 / bpm
            var bar = 0
            while (offset + bar * 4 * beat < seconds) {
                val t = offset + bar * 4 * beat
                s.kick(t)
                s.kick(t + 2.5 * beat, 0.7)
                s.snare(t + beat)
                s.snare(t + 3 * beat)
                for (e in 0 until 8) {
                    val swing = if (e % 2 == 1) beat * 0.08 else 0.0
                    s.hat(t + e * beat / 2 + swing, 0.1)
                }
                s.chord(t, beat * 4, A_MINOR_PROGRESSION[bar % 4], 0.05)
                bar++
            }
            s.noise(0.003)
            return s
        }

        fun dnb(bpm: Double, offset: Double, seconds: Double = 120.0, sr: Int = 11025): Synth {
            val s = Synth(sr, seconds)
            val beat = 60.0 / bpm
            var bar = 0
            while (offset + bar * 4 * beat < seconds) {
                val t = offset + bar * 4 * beat
                s.kick(t)
                s.kick(t + 2.5 * beat, 0.8)
                s.snare(t + beat, 0.45)
                s.snare(t + 3 * beat, 0.45)
                for (e in 0 until 8) s.hat(t + e * beat / 2, 0.12)
                bar++
            }
            s.noise(0.003)
            return s
        }
    }
}
