package com.autodj.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tan

/** What the engine needs to know about a song (from the library analysis). */
data class TrackInfo(
    val id: Long,
    val title: String,
    val artist: String,
    val durationSec: Double,
    val bpm: Double?,
    val firstBeat: Double?,
    val downbeatOffset: Int,
    val beatConfidence: Double,
    val loudnessDb: Double?,
    /** Per-block loudness in dB, full band then low band, interleaved (see TrackAnalysis.beatEnergy). */
    val blockEnergy: FloatArray,
    val keyIndex: Int? = null,
    val energy: Double? = null,
) {
    val beatable: Boolean get() = bpm != null && firstBeat != null && beatConfidence >= 0.35
    val beatLength: Double get() = 60.0 / (bpm ?: 120.0)
    /** Length in seconds of each blockEnergy entry. */
    val blockLength: Double get() = if (bpm != null) 60.0 / bpm else 0.5
    val blockStart: Double get() = if (bpm != null) firstBeat ?: 0.0 else 0.0
    val blocks: Int get() = blockEnergy.size / 2

    override fun equals(other: Any?) = other is TrackInfo && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * Splits off the bass (< ~150 Hz) with a 4th-order Linkwitz–Riley low-pass so it can be turned
 * down. Output = input - (1 - lowGain) * low, which is exactly the input when lowGain = 1.
 */
class BassFilter(sampleRate: Int, cutoff: Double = 150.0) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    // Two cascaded biquads per channel: state [stage][channel][2]
    private val z = Array(2) { Array(2) { DoubleArray(2) } }

    init {
        val k = tan(PI * cutoff / sampleRate)
        val q = 1 / sqrt(2.0)
        val norm = 1 / (1 + k / q + k * k)
        b0 = k * k * norm
        b1 = 2 * b0
        b2 = b0
        a1 = 2 * (k * k - 1) * norm
        a2 = (1 - k / q + k * k) * norm
    }

    private fun stage(s: Int, ch: Int, x: Double): Double {
        val st = z[s][ch]
        val y = b0 * x + st[0]
        st[0] = b1 * x - a1 * y + st[1]
        st[1] = b2 * x - a2 * y
        return y
    }

    /** In-place over [frames] stereo frames; lowGain ramps linearly from [from] to [to]. */
    fun process(buf: FloatArray, frames: Int, from: Float, to: Float) {
        for (i in 0 until frames) {
            val g = from + (to - from) * i / frames
            for (ch in 0..1) {
                val x = buf[2 * i + ch].toDouble()
                val low = stage(1, ch, stage(0, ch, x))
                // Keep filter state running even when not cutting, so engaging it is click-free.
                if (g < 0.999f) buf[2 * i + ch] = (x - (1 - g) * low).toFloat()
            }
        }
    }
}

/** Gentle peak limiter so two overlapping songs never clip. */
class Limiter(sampleRate: Int, private val ceiling: Float = 0.94f) {
    private var gain = 1f
    private val release = exp(-1.0 / (0.25 * sampleRate)).toFloat()

    fun process(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val peak = maxOf(abs(buf[2 * i]), abs(buf[2 * i + 1]))
            val needed = if (peak * gain > ceiling) ceiling / peak else 1f
            gain = if (needed < gain) needed else 1f - (1f - gain) * release
            buf[2 * i] *= gain
            buf[2 * i + 1] *= gain
        }
    }
}

/** One playing song: source → time stretch → bass filter → gain. */
class Deck(val track: TrackInfo, private val sampleRate: Int, private val source: PcmSource) {
    private val stretch = Wsola(sampleRate, source)
    private val bass = BassFilter(sampleRate)
    private val pending = FloatArray(stretch.hop * 2)
    private var pendingFrames = 0
    private var pendingPos = 0

    /** Playback rate (1.0 = the song's own tempo). */
    var rate: Double
        get() = stretch.rate
        set(v) {
            stretch.rate = v
        }

    /** Bass level 0..1 (ramped smoothly between render calls). */
    var lowGain = 1f
    private var lastLow = 1f

    /** Loudness matching so songs play at a similar level. */
    val levelGain: Float = track.loudnessDb?.let {
        Math.pow(10.0, (TARGET_DB - it).coerceIn(-6.0, 6.0) / 20.0).toFloat()
    } ?: 1f

    /** Song time (seconds) of the audio about to be rendered. */
    val positionSec: Double
        get() = (stretch.position - (pendingFrames - pendingPos) * rate) / sampleRate

    val finished: Boolean get() = stretch.finished && pendingPos >= pendingFrames

    /** Audio is available to play (the decoder has caught up). */
    val ready: Boolean get() = source.isReady()
    val underruns: Int get() = stretch.underruns

    fun start(atSec: Double) {
        stretch.start((atSec * sampleRate).toLong().coerceAtLeast(0))
    }

    /** Renders [frames] stereo frames into [dst] (overwrites), level-matched and bass-filtered. */
    fun render(dst: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            if (pendingPos >= pendingFrames) {
                stretch.produce(pending, 0)
                pendingFrames = stretch.hop
                pendingPos = 0
            }
            val n = minOf(frames - done, pendingFrames - pendingPos)
            System.arraycopy(pending, pendingPos * 2, dst, done * 2, n * 2)
            pendingPos += n
            done += n
        }
        bass.process(dst, frames, lastLow, lowGain)
        lastLow = lowGain
        if (levelGain != 1f) for (i in 0 until frames * 2) dst[i] *= levelGain
    }

    fun close() = source.close()

    companion object {
        const val TARGET_DB = -16.0
    }
}
