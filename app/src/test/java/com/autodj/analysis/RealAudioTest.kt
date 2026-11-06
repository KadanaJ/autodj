package com.autodj.analysis

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs the analyzer over 16-bit mono WAVs in $AUTODJ_WAV_DIR (skipped when unset). */
class RealAudioTest {
    @Test
    fun analyzeFolder() {
        val dir = System.getenv("AUTODJ_WAV_DIR")
        assumeTrue(dir != null)
        File(dir!!).listFiles { f -> f.name.endsWith(".wav") }!!.sorted().forEach { f ->
            val bytes = f.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val sr = bb.getInt(24)
            var pos = 12
            var dataStart = 0
            var dataLen = 0
            while (pos < bytes.size - 8) {
                val id = String(bytes, pos, 4)
                val len = bb.getInt(pos + 4)
                if (id == "data") { dataStart = pos + 8; dataLen = minOf(len, bytes.size - dataStart); break }
                pos += 8 + len
            }
            val x = FloatArray(dataLen / 2) { bb.getShort(dataStart + it * 2) / 32768f }
            val t0 = System.nanoTime()
            val a = TrackAnalyzer.analyze(x, sr)
            val ms = (System.nanoTime() - t0) / 1e6
            val g = a.grid
            println("REAL %-34s dur=%5.1fs bpm=%7.2f conf=%.2f first=%.3f down=%d key=%-3s(%.2f) energy=%.2f [%.0f ms]".format(
                f.name, a.durationSec, g?.bpm ?: 0.0, g?.confidence ?: 0.0, g?.firstBeat ?: 0.0, g?.downbeatOffset ?: -1,
                a.keyIndex?.let { KeyDetector.name(it) } ?: "-", a.keyConfidence, a.energy, ms))
        }
    }
}
