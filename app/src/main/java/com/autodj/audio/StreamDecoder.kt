package com.autodj.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.autodj.analysis.Decimator
import java.io.Closeable
import java.nio.ByteOrder

class DecodeException(message: String) : Exception(message)

/**
 * Pulls decoded PCM out of any format Android can decode (MP3, AAC/M4A, FLAC, OGG/Opus, WAV…)
 * as interleaved floats.
 */
class StreamDecoder(context: Context, uri: Uri) : Closeable {
    class Chunk(val samples: FloatArray, val presentationUs: Long)

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

    var sampleRate: Int
        private set
    var channels: Int
        private set
    val durationUs: Long

    init {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("Can't open file: ${e.message}")
        }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) {
            extractor.release()
            throw DecodeException("No audio track")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("Unsupported format $mime")
        }
    }

    fun seekTo(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
    }

    /** Next block of interleaved samples, or null at the end of the stream. */
    fun read(): Chunk? {
        while (!outputDone) {
            if (!inputDone) feed()
            val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var chunk: Chunk? = null
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(idx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val samples = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            FloatArray(fb.remaining()).also { fb.get(it) }
                        } else {
                            val sb = buf.asShortBuffer()
                            FloatArray(sb.remaining()) { sb.get() / 32768f }
                        }
                        chunk = Chunk(samples, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) outputDone = true
                    if (chunk != null) return chunk
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
            }
        }
        return null
    }

    private fun feed() {
        val idx = codec.dequeueInputBuffer(TIMEOUT_US)
        if (idx < 0) return
        val buf = codec.getInputBuffer(idx)!!
        val size = extractor.readSampleData(buf, 0)
        if (size < 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}

object AnalysisDecoder {
    class Mono(val samples: FloatArray, val sampleRate: Int)

    /** Decodes to ~11 kHz mono, stopping after [maxSeconds]. */
    fun decode(context: Context, uri: Uri, maxSeconds: Double, isCancelled: () -> Boolean = { false }): Mono {
        StreamDecoder(context, uri).use { d ->
            var decimator: Decimator? = null
            var frames = 0L
            while (true) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException()
                val chunk = d.read() ?: break
                val dec = decimator ?: Decimator(d.sampleRate).also { decimator = it }
                val ch = d.channels
                val s = chunk.samples
                val n = s.size / ch
                if (ch == 1) {
                    for (i in 0 until n) dec.push(s[i])
                } else {
                    val inv = 1f / ch
                    for (i in 0 until n) {
                        var sum = 0f
                        val base = i * ch
                        for (k in 0 until ch) sum += s[base + k]
                        dec.push(sum * inv)
                    }
                }
                frames += n
                if (frames > maxSeconds * d.sampleRate) break
            }
            val dec = decimator ?: throw DecodeException("No audio decoded")
            return Mono(dec.output.toArray(), dec.outputRate)
        }
    }
}
