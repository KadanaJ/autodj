package com.autodj.engine

import com.autodj.analysis.Decimator
import com.autodj.analysis.TrackAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class EngineTest {
    private val sr = 44100

    /** Stereo track: either low kicks (deep) or high clicks on every beat, plus a pad. */
    private fun beatTrack(bpm: Double, offset: Double, seconds: Double, high: Boolean, seed: Int = 1): FloatArray {
        val n = (seconds * sr).toInt()
        val mono = FloatArray(n)
        val beat = 60.0 / bpm
        var i = 0
        while (offset + i * beat < seconds) {
            val s = ((offset + i * beat) * sr).toInt()
            val len = (0.12 * sr).toInt()
            var ph = 0.0
            for (k in 0 until len) {
                if (s + k >= n) break
                val t = k.toDouble() / sr
                if (high) {
                    mono[s + k] += (0.5 * sin(2 * PI * 3500 * t) * exp(-t * 80)).toFloat()
                } else {
                    ph += 2 * PI * (45 + 90 * exp(-t * 35)) / sr
                    mono[s + k] += (0.8 * sin(ph) * exp(-t * 14)).toFloat()
                }
            }
            i++
        }
        // quiet sustained tone so the track has "music" throughout
        val f = if (high) 660.0 else 220.0
        for (k in 0 until n) mono[k] += (0.03 * sin(2 * PI * f * k / sr + seed)).toFloat()
        return FloatArray(n * 2) { mono[it / 2] }
    }

    /** Runs the phone's analysis path (decimate to ~11 kHz, analyse) to build TrackInfo. */
    private fun info(id: Long, stereo: FloatArray, firstBeatError: Double = 0.0): TrackInfo {
        val d = Decimator(sr)
        for (i in 0 until stereo.size / 2) d.push((stereo[2 * i] + stereo[2 * i + 1]) / 2)
        val a = TrackAnalyzer.analyze(d.output.toArray(), d.outputRate)
        val g = a.grid!!
        return TrackInfo(
            id = id, title = "t$id", artist = "", durationSec = stereo.size / 2.0 / sr,
            bpm = g.bpm, firstBeat = g.firstBeat + firstBeatError, downbeatOffset = g.downbeatOffset,
            beatConfidence = g.confidence, loudnessDb = a.loudnessDb, blockEnergy = a.beatEnergy,
        )
    }

    private class Feed(private val tracks: List<Pair<TrackInfo, FloatArray>>) : TrackFeed {
        override fun next(current: TrackInfo): TrackInfo? {
            val i = tracks.indexOfFirst { it.first == current }
            return tracks.getOrNull(i + 1)?.first
        }

        override fun open(track: TrackInfo): PcmSource = ArraySource(tracks.first { it.first == track }.second)
    }

    private class Render(val out: FloatArray, val statuses: List<EngineStatus>)

    private fun render(engine: MixEngine, seconds: Double): Render {
        val block = 1024
        val total = (seconds * sr).toInt() / block * block
        val out = FloatArray(total * 2)
        val buf = FloatArray(block * 2)
        val st = ArrayList<EngineStatus>()
        var f = 0
        while (f < total) {
            engine.render(buf, block)
            System.arraycopy(buf, 0, out, f * 2, block * 2)
            st += engine.status
            f += block
        }
        return Render(out, st)
    }

    /** Onset times (s) of energy in a band, from the mono mix. */
    private fun onsets(stereo: FloatArray, high: Boolean): List<Double> {
        val n = stereo.size / 2
        // one-pole filters
        var lp = 0.0
        var lp2 = 0.0
        val hop = 32
        val env = DoubleArray(n / hop)
        var acc = 0.0
        for (i in 0 until n) {
            val x = (stereo[2 * i] + stereo[2 * i + 1]) / 2.0
            lp += 0.02 * (x - lp)
            lp2 += 0.3 * (x - lp2)
            val band = if (high) x - lp2 else lp
            acc += band * band
            if ((i + 1) % hop == 0) {
                env[i / hop] = acc
                acc = 0.0
            }
        }
        val max = env.max()
        val out = ArrayList<Double>()
        var last = -1e9
        for (t in 1 until env.size) {
            val th = max * 0.02
            if (env[t] > th && env[t - 1] <= th) {
                val time = t * hop.toDouble() / sr
                if (time - last > 0.2) {
                    out += time
                    last = time
                }
            }
        }
        return out
    }

    @Test
    fun wsolaAtRateOneIsTransparent() {
        val x = beatTrack(120.0, 0.1, 5.0, high = true)
        val w = Wsola(sr, ArraySource(x))
        w.start(0)
        val out = FloatArray(sr * 2 * 4)
        var f = 0
        while (f + w.hop <= sr * 4) {
            w.produce(out, f)
            f += w.hop
        }
        // After the first half window, output equals input delayed by 0.
        var maxErr = 0f
        for (i in w.frameSize * 2 until f * 2) maxErr = maxOf(maxErr, abs(out[i] - x[i]))
        assertTrue("maxErr $maxErr", maxErr < 1e-4)
    }

    @Test
    fun wsolaChangesTempoNotPitch() {
        // 1 kHz tone with clicks at 120 BPM, played at 1.05x.
        val n = sr * 10
        val x = FloatArray(n * 2)
        for (i in 0 until n) {
            val v = (0.3 * sin(2 * PI * 1000 * i / sr)).toFloat()
            x[2 * i] = v; x[2 * i + 1] = v
        }
        val w = Wsola(sr, ArraySource(x))
        w.start(0)
        w.rate = 1.05
        val outFrames = (n / 1.05).toInt() / w.hop * w.hop - w.hop * 4
        val out = FloatArray(outFrames * 2)
        var f = 0
        while (f < outFrames) {
            w.produce(out, f); f += w.hop
        }
        var zc = 0
        for (i in sr until 2 * sr) if ((out[2 * (i - 1)] < 0) != (out[2 * i] < 0)) zc++
        assertEquals("pitch preserved (zero crossings per second)", 2000.0, zc.toDouble(), 20.0)
        assertEquals("consumed input", outFrames * 1.05, w.position, w.hop * 2.0)
    }

    @Test
    fun beatmatchedBlendKeepsBeatsTogether() {
        // A: 125 BPM low kicks. B: 128.5 BPM high clicks; B's grid deliberately 12 ms off.
        val xa = beatTrack(125.0, 0.31, 70.0, high = false)
        val xb = beatTrack(128.5, 0.53, 70.0, high = true, seed = 2)
        val ta = info(1, xa)
        val tb = info(2, xb, firstBeatError = 0.012)
        val engine = MixEngine(sr, Feed(listOf(ta to xa, tb to xb)))
        engine.play(ta)
        val r = render(engine, 95.0)
        val plan = r.statuses.first { it.plan != null }.plan!!
        println("PLAN $plan  aBpm=${ta.bpm} bBpm=${tb.bpm}")
        assertEquals(TransitionType.BEATMATCH, plan.type)
        assertEquals(32, plan.beats)

        // A's phrase boundary
        val aBeat = (plan.aStart - ta.firstBeat!!) / ta.beatLength
        assertEquals(0, ((aBeat.toInt() - ta.downbeatOffset) % 16 + 16) % 16)

        // Measure beat alignment in the middle part of the blend.
        val blendStatuses = r.statuses.withIndex().filter { it.value.blend in 0.0..1.0 }
        val startT = blendStatuses.first().index * 1024.0 / sr
        val endT = blendStatuses.last().index * 1024.0 / sr
        val lows = onsets(r.out, high = false).filter { it in startT + (endT - startT) * 0.4..startT + (endT - startT) * 0.8 }
        val highs = onsets(r.out, high = true)
        val diffs = lows.map { l -> highs.minOf { abs(it - l) } }
        val med = diffs.sorted()[diffs.size / 2]
        println("ALIGN blend %.1f-%.1fs lows=%d median=%.1fms max=%.1fms aligner=%.1fms".format(startT, endT, lows.size, med * 1000, diffs.max() * 1000, r.statuses.last().alignmentMs))
        assertTrue("median offset ${med * 1000} ms", med < 0.005)
        assertTrue("max offset ${diffs.max() * 1000} ms", diffs.max() < 0.012)

        // After the blend B glides back to its own tempo.
        val last = r.statuses.last()
        assertEquals(2L, last.current!!.id)
        assertEquals(tb.bpm!!, last.tempoBpm!!, 0.05)
        assertEquals(0, r.statuses.maxOf { it.underruns })
        // No clipping.
        assertTrue(r.out.maxOf { abs(it) } <= 0.95f)
    }

    @Test
    fun farTempoGapCrossfades() {
        val xa = beatTrack(100.0, 0.2, 40.0, high = false)
        val xb = beatTrack(128.0, 0.4, 40.0, high = true)
        val ta = info(1, xa)
        val tb = info(2, xb)
        val p = TransitionPlanner.plan(ta, tb, 5.0)
        assertEquals(TransitionType.CROSSFADE, p.type)
        val engine = MixEngine(sr, Feed(listOf(ta to xa, tb to xb)))
        engine.play(ta)
        val r = render(engine, 45.0)
        assertEquals(2L, r.statuses.last().current!!.id)
        assertEquals(128.0, r.statuses.last().tempoBpm!!, 0.05)
    }

    @Test
    fun halfTimeMatches() {
        val xa = beatTrack(128.0, 0.2, 40.0, high = false)
        val xb = beatTrack(65.0, 0.4, 40.0, high = true)
        val ta = info(1, xa)
        val tb = info(2, xb).copy(bpm = 65.0)
        val m = TransitionPlanner.beatRatio(ta, tb)!!
        assertEquals(0.5, m.first, 1e-9)
        assertEquals(128.0 * 0.5 / 65.0, m.second, 1e-9)
    }

    @Test
    fun skipStartsQuickBlend() {
        val xa = beatTrack(124.0, 0.3, 120.0, high = false)
        val xb = beatTrack(124.0, 0.1, 60.0, high = true)
        val ta = info(1, xa)
        val tb = info(2, xb)
        val engine = MixEngine(sr, Feed(listOf(ta to xa, tb to xb)))
        engine.play(ta)
        render(engine, 3.0)
        engine.skip()
        val r = render(engine, 15.0)
        assertEquals("switched within 15 s of skip", 2L, r.statuses.last().current!!.id)
    }
}
