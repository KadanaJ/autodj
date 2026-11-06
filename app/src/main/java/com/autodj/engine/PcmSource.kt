package com.autodj.engine

/**
 * Sequential stereo PCM at the engine's sample rate. Implementations must not block for long:
 * when audio is not ready yet they may return fewer frames (the engine pads with silence).
 */
interface PcmSource {
    /** Reads up to [frames] interleaved stereo frames into [dst] at [offset]. Returns frames read, or -1 at end. */
    fun read(dst: FloatArray, offset: Int, frames: Int): Int

    /** Skips forward [frames] frames (may decode and discard). */
    fun skip(frames: Long)

    /** False while a background decoder is still filling its first buffer (or seeking). */
    fun isReady(): Boolean = true

    fun close() {}
}

/** In-memory source, used by tests and offline rendering. */
class ArraySource(private val stereo: FloatArray) : PcmSource {
    private var pos = 0

    override fun read(dst: FloatArray, offset: Int, frames: Int): Int {
        val left = stereo.size / 2 - pos
        if (left <= 0) return -1
        val n = minOf(frames, left)
        System.arraycopy(stereo, pos * 2, dst, offset * 2, n * 2)
        pos += n
        return n
    }

    override fun skip(frames: Long) {
        pos = minOf(stereo.size / 2.toLong(), pos + frames).toInt()
    }
}

/** Streaming stereo resampler (cubic Hermite), used when a file's rate differs from the output rate. */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var frac = 0.0
    // Last 4 input frames (stereo) for interpolation.
    private val hist = FloatArray(8)
    private var primed = 0

    /** Converts [frames] interleaved stereo frames; appends output to [sink]. */
    fun process(src: FloatArray, frames: Int, sink: (Float, Float) -> Unit) {
        if (inRate == outRate) {
            for (i in 0 until frames) sink(src[2 * i], src[2 * i + 1])
            return
        }
        for (i in 0 until frames) {
            // shift history
            System.arraycopy(hist, 2, hist, 0, 6)
            hist[6] = src[2 * i]
            hist[7] = src[2 * i + 1]
            if (primed < 4) {
                primed++
                if (primed < 4) continue
            }
            // Interpolate between hist[1] and hist[2] (frames x0..x3 = hist 0..3).
            while (frac < 1.0) {
                val t = frac.toFloat()
                sink(hermite(hist[0], hist[2], hist[4], hist[6], t), hermite(hist[1], hist[3], hist[5], hist[7], t))
                frac += step
            }
            frac -= 1.0
        }
    }

    private fun hermite(x0: Float, x1: Float, x2: Float, x3: Float, t: Float): Float {
        val c1 = 0.5f * (x2 - x0)
        val c2 = x0 - 2.5f * x1 + 2f * x2 - 0.5f * x3
        val c3 = 0.5f * (x3 - x0) + 1.5f * (x1 - x2)
        return ((c3 * t + c2) * t + c1) * t + x1
    }
}
