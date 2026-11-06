package com.autodj.engine

import com.autodj.analysis.Decimator
import com.autodj.analysis.Synth
import com.autodj.analysis.TrackAnalyzer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders a listening demo to $AUTODJ_DEMO_DIR/mix.wav (skipped when unset). */
class DemoRenderTest {
    private val sr = 44100

    private fun readWav(f: File): FloatArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos < bytes.size - 8) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "data") {
                val start = pos + 8
                val n = minOf(len, bytes.size - start) / 2
                return FloatArray(n) { bb.getShort(start + it * 2) / 32768f }
            }
            pos += 8 + len
        }
        error("no data")
    }

    private fun writeWav(f: File, stereo: FloatArray) {
        val data = ByteBuffer.allocate(stereo.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in stereo) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        f.writeBytes(h.array() + data.array())
    }

    private fun stereo(mono: FloatArray) = FloatArray(mono.size * 2) { mono[it / 2] * 0.8f }

    private fun info(id: Long, title: String, x: FloatArray): TrackInfo {
        val d = Decimator(sr)
        for (i in 0 until x.size / 2) d.push((x[2 * i] + x[2 * i + 1]) / 2)
        val a = TrackAnalyzer.analyze(d.output.toArray(), d.outputRate)
        val g = a.grid!!
        println("DEMO $title bpm=${g.bpm} conf=${g.confidence} first=${g.firstBeat}")
        return TrackInfo(id, title, "", x.size / 2.0 / sr, g.bpm, g.firstBeat, g.downbeatOffset, g.confidence, a.loudnessDb, a.beatEnergy)
    }

    @Test
    fun renderDemo() {
        val dir = System.getenv("AUTODJ_DEMO_DIR")
        assumeTrue(dir != null)
        val house = stereo(Synth.house(128.0, 0.4, 70.0, sr).out)
        val vibe = readWav(File(dir, "vibeace.wav"))
        val house2 = stereo(Synth.house(126.0, 0.2, 60.0, sr).out)
        val tracks = listOf(info(1, "Synth house 128", house) to house, info(2, "Vibe Ace", vibe) to vibe, info(3, "Synth house 126", house2) to house2)
        val engine = MixEngine(sr, object : TrackFeed {
            override fun next(current: TrackInfo) = tracks.getOrNull(tracks.indexOfFirst { it.first == current } + 1)?.first
            override fun open(track: TrackInfo): PcmSource = ArraySource(tracks.first { it.first == track }.second)
        })
        engine.play(tracks[0].first)
        val block = 1024
        val out = ArrayList<FloatArray>()
        var last: EngineStatus? = null
        var t = 0.0
        while (!engine.isIdle && t < 400) {
            val buf = FloatArray(block * 2)
            engine.render(buf, block)
            out += buf
            val s = engine.status
            if (s.plan != last?.plan || s.current != last?.current) {
                println("DEMO t=%.1fs now=%s next=%s plan=%s".format(t, s.current?.title, s.next?.title, s.plan?.let { "${it.type} A@%.1fs B@%.1fs %d beats rate=%.3f".format(it.aStart, it.bStart, it.beats, it.bRateRelative) }))
            }
            if (s.blend >= 0 && (last?.blend ?: -1.0) < 0) println("DEMO t=%.1fs blend starts".format(t))
            last = s
            t += block.toDouble() / sr
        }
        val all = FloatArray(out.size * block * 2)
        out.forEachIndexed { i, b -> System.arraycopy(b, 0, all, i * block * 2, block * 2) }
        writeWav(File(dir, "mix.wav"), all)
        println("DEMO wrote %.1fs aligner=%.1fms".format(all.size / 2.0 / sr, engine.status.alignmentMs))
    }
}
