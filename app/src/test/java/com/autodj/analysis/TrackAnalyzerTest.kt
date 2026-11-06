package com.autodj.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class TrackAnalyzerTest {
    private val sr = 11025

    /** Distance between detected and true beat phase, in seconds, modulo one beat. */
    private fun phaseError(grid: BeatGrid, trueOffset: Double): Double {
        val beat = grid.beatLength
        val d = ((grid.firstBeat - trueOffset) % beat + beat) % beat
        return minOf(d, beat - d)
    }

    private fun report(name: String, a: TrackAnalysis, trueOffset: Double? = null) {
        val g = a.grid
        println(
            "%-22s bpm=%7.3f conf=%.2f first=%.4f%s key=%s(%.2f) energy=%.2f loud=%.1fdB onsets=%.2f/s bright=%.3f".format(
                name, g?.bpm ?: 0.0, g?.confidence ?: 0.0, g?.firstBeat ?: 0.0,
                if (trueOffset != null && g != null) " phaseErr=%.1fms".format(phaseError(g, trueOffset) * 1000) else "",
                a.keyIndex?.let { KeyDetector.name(it) + "/" + KeyDetector.camelot(it) }, a.keyConfidence,
                a.energy, a.loudnessDb, a.onsetRate, a.brightness,
            ),
        )
    }

    @Test
    fun houseAt128() {
        val a = TrackAnalyzer.analyze(Synth.house(128.0, 0.37).out, sr)
        report("house 128", a, 0.37)
        val g = assertGrid(a)
        assertEquals(128.0, g.bpm, 0.02)
        assertTrue("phase", phaseError(g, 0.37) < 0.012)
        assertTrue("confidence ${g.confidence}", g.confidence > 0.6)
        assertEquals("A minor", 21, a.keyIndex)
        assertEquals("8A", KeyDetector.camelot(a.keyIndex!!))
    }

    @Test
    fun fractionalTempo() {
        val a = TrackAnalyzer.analyze(Synth.house(123.4, 1.05).out, sr)
        report("house 123.4", a, 1.05)
        val g = assertGrid(a)
        assertEquals(123.4, g.bpm, 0.02)
        assertTrue("phase", phaseError(g, 1.05) < 0.012)
    }

    @Test
    fun manyHouseTempos() {
        for (bpm in listOf(100.0, 112.0, 120.0, 124.0, 126.0, 130.0, 135.0, 140.0, 150.0)) {
            val off = (bpm % 7) / 10
            val a = TrackAnalyzer.analyze(Synth.house(bpm, off, 90.0).out, sr)
            report("house $bpm", a, off)
            val g = assertGrid(a)
            assertEquals("bpm for $bpm", bpm, g.bpm, 0.03)
            assertTrue("phase for $bpm", phaseError(g, off) < 0.012)
        }
    }

    @Test
    fun hipHopAt90() {
        val a = TrackAnalyzer.analyze(Synth.hipHop(90.0, 0.2).out, sr)
        report("hiphop 90", a, 0.2)
        val g = assertGrid(a)
        assertEquals(90.0, g.bpm, 0.05)
        assertTrue("phase", phaseError(g, 0.2) < 0.015)
    }

    @Test
    fun drumAndBassAt174() {
        val a = TrackAnalyzer.analyze(Synth.dnb(174.0, 0.5).out, sr)
        report("dnb 174", a, 0.5)
        val g = assertGrid(a)
        // Half-time (87) is an acceptable reading for DnB; the user can flip it with x2.
        val ok = abs(g.bpm - 174.0) < 0.05 || abs(g.bpm - 87.0) < 0.05
        assertTrue("bpm ${g.bpm}", ok)
    }

    @Test
    fun noBeatGivesLowConfidence() {
        val s = Synth(sr, 90.0, seed = 7)
        var t = 0.0
        var bar = 0
        while (t < 90) {
            s.chord(t, 6.0, Synth.A_MINOR_PROGRESSION[bar % 4], 0.08)
            t += 5.3
            bar++
        }
        s.noise(0.01)
        val a = TrackAnalyzer.analyze(s.out, sr)
        report("ambient", a)
        assertTrue("confidence ${a.grid?.confidence}", (a.grid?.confidence ?: 0.0) < 0.3)
    }

    @Test
    fun driftingTempoGivesLowConfidence() {
        val s = Synth(sr, 120.0)
        var t = 0.3
        var i = 0
        while (t < 120) {
            s.kick(t)
            s.hat(t + 0.2)
            val bpm = 112 + 14 * (t / 120) // live drummer speeding up
            t += 60 / bpm
            i++
        }
        val a = TrackAnalyzer.analyze(s.out, sr)
        report("drifting 112->126", a)
        assertTrue("confidence ${a.grid?.confidence}", (a.grid?.confidence ?: 0.0) < 0.35)
    }

    @Test
    fun energyOrdersQuietBelowLoud() {
        val quiet = Synth(sr, 60.0)
        var t = 0.0
        while (t < 60) {
            quiet.chord(t, 4.0, Synth.A_MINOR_PROGRESSION[(t / 4).toInt() % 4], 0.02)
            quiet.kick(t, 0.1)
            t += 60.0 / 75 * 2
        }
        val loud = Synth.dnb(174.0, 0.0, 60.0)
        val eq = TrackAnalyzer.analyze(quiet.out, sr)
        val el = TrackAnalyzer.analyze(loud.out, sr)
        report("quiet", eq)
        report("loud", el)
        assertTrue(eq.energy + 0.3 < el.energy)
    }

    @Test
    fun beatEnergyHasOneEntryPairPerBeat() {
        val a = TrackAnalyzer.analyze(Synth.house(120.0, 0.0, 60.0).out, sr)
        val beats = a.beatEnergy.size / 2
        assertTrue("beats $beats", beats in 118..120)
    }

    @Test
    fun tapHintSelectsTempoFamily() {
        val x = Synth.house(128.0, 0.37).out
        // A user tapping along roughly at half time gets a precise 64 grid in phase with the kicks.
        val g = TrackAnalyzer.analyze(x, sr, bpmHint = 63.0).grid!!
        assertEquals(64.0, g.bpm, 0.02)
        val g2 = TrackAnalyzer.analyze(x, sr, bpmHint = 129.5).grid!!
        assertEquals(128.0, g2.bpm, 0.02)
        assertTrue(phaseError(g2, 0.37) < 0.012)
    }

    @Test
    fun camelotWheel() {
        assertEquals("8B", KeyDetector.camelot(0)) // C
        assertEquals("9B", KeyDetector.camelot(7)) // G
        assertEquals("8A", KeyDetector.camelot(21)) // Am
        assertEquals("5A", KeyDetector.camelot(12)) // Cm
        assertEquals("12B", KeyDetector.camelot(4)) // E
        assertEquals("1B", KeyDetector.camelot(11)) // B
    }

    @Test
    fun decimatorKeepsFrequencyAndRate() {
        val d = Decimator(44100)
        assertEquals(4, d.factor)
        assertEquals(11025, d.outputRate)
        for (i in 0 until 44100 * 2) d.push(sin(2 * PI * 1000 * i / 44100).toFloat())
        val y = d.output.toArray()
        assertEquals(22050, y.size)
        // Count zero crossings in the second second: 1 kHz -> ~2000.
        var zc = 0
        for (i in 11026 until 22050) if ((y[i - 1] < 0) != (y[i] < 0)) zc++
        assertEquals(2000.0, zc.toDouble(), 4.0)
        // Content above the new Nyquist must be strongly attenuated.
        val d2 = Decimator(44100)
        for (i in 0 until 44100) d2.push(sin(2 * PI * 9000 * i / 44100).toFloat())
        val y2 = d2.output.toArray()
        val peak = y2.drop(1000).maxOf { abs(it) }
        assertTrue("alias $peak", peak < 0.02)
        assertEquals(12000, Decimator(48000).outputRate)
        assertEquals(1, (Decimator(11025).factor.toDouble()).roundToInt())
    }

    private fun assertGrid(a: TrackAnalysis): BeatGrid {
        assertNotNull("grid", a.grid)
        return a.grid!!
    }
}
