package com.autodj.player

import android.content.Context
import android.net.Uri
import android.os.Process
import com.autodj.audio.StreamDecoder
import com.autodj.engine.PcmSource
import com.autodj.engine.Resampler

/**
 * Decodes a song on its own thread into a ring buffer (stereo, engine sample rate), so the
 * audio thread never waits on the decoder.
 */
class DecoderSource(
    private val context: Context,
    private val uri: Uri,
    private val outRate: Int,
    seconds: Int = 10,
) : PcmSource {
    private val capacity = outRate * seconds
    private val ring = FloatArray(capacity * 2)
    private var readPos = 0L // frames consumed
    private var writePos = 0L // frames produced
    private var skipRemaining = 0L
    private var ended = false
    private var started = false

    @Volatile private var closed = false
    private val lock = Object()
    private val thread = Thread({ run() }, "decoder")

    init {
        thread.start()
    }

    override fun read(dst: FloatArray, offset: Int, frames: Int): Int = synchronized(lock) {
        val avail = (writePos - readPos).toInt()
        if (avail == 0) return if (ended && skipRemaining == 0L) -1 else 0
        val n = minOf(frames, avail)
        for (i in 0 until n) {
            val r = ((readPos + i) % capacity).toInt() * 2
            dst[(offset + i) * 2] = ring[r]
            dst[(offset + i) * 2 + 1] = ring[r + 1]
        }
        readPos += n
        lock.notifyAll()
        n
    }

    override fun skip(frames: Long) = synchronized(lock) {
        val avail = writePos - readPos
        val drop = minOf(avail, frames)
        readPos += drop
        skipRemaining += frames - drop
        lock.notifyAll()
    }

    override fun isReady(): Boolean = synchronized(lock) {
        ended || (skipRemaining == 0L && started && writePos - readPos >= outRate / 4)
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
    }

    private fun push(l: Float, r: Float) {
        synchronized(lock) {
            if (skipRemaining > 0) {
                skipRemaining--
                return
            }
            while (writePos - readPos >= capacity && !closed) lock.wait(200)
            if (closed) return
            val w = (writePos % capacity).toInt() * 2
            ring[w] = l
            ring[w + 1] = r
            writePos++
            started = true
        }
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            StreamDecoder(context, uri).use { dec ->
                var resampler: Resampler? = null
                var stereo = FloatArray(0)
                while (!closed) {
                    val chunk = dec.read() ?: break
                    val rs = resampler ?: Resampler(dec.sampleRate, outRate).also { resampler = it }
                    val ch = dec.channels
                    val frames = chunk.samples.size / ch
                    if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                    for (i in 0 until frames) {
                        val l = chunk.samples[i * ch]
                        stereo[2 * i] = l
                        stereo[2 * i + 1] = if (ch > 1) chunk.samples[i * ch + 1] else l
                    }
                    rs.process(stereo, frames) { l, r -> push(l, r) }
                }
            }
        } catch (e: Exception) {
            // Unreadable file: treat as ended so the engine moves on.
        } finally {
            synchronized(lock) {
                ended = true
                started = true
                lock.notifyAll()
            }
        }
    }
}
