package com.autodj.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.autodj.audio.StreamDecoder
import com.autodj.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Plays one song with optional clicks mixed in on the detected beat grid, so the user can hear
 * whether the analysis is right. (The real two-deck mixing engine comes in step 2.)
 */
class PreviewPlayer(private val context: Context) {
    data class State(
        val trackId: Long? = null,
        val playing: Boolean = false,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val clicks: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var session = 0
    @Volatile private var clicksOn = true
    private var thread: Thread? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()

    fun setClicks(on: Boolean) {
        clicksOn = on
        _state.value = _state.value.copy(clicks = on)
    }

    fun play(track: Track, startSec: Double) {
        stop()
        audioManager.requestAudioFocus(focusRequest)
        val mySession = ++session
        _state.value = State(
            trackId = track.id, playing = true, positionSec = startSec,
            durationSec = track.durationMs / 1000.0, clicks = clicksOn,
        )
        thread = Thread({ runPlayback(track, startSec, mySession) }, "preview").apply { start() }
    }

    fun stop() {
        session++
        thread?.join(500)
        thread = null
        if (_state.value.playing) _state.value = _state.value.copy(playing = false)
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun runPlayback(track: Track, startSec: Double, mySession: Int) {
        var audio: AudioTrack? = null
        try {
            StreamDecoder(context, track.uri).use { dec ->
                if (startSec > 0) dec.seekTo((startSec * 1e6).toLong())
                val first = dec.read()
                if (first == null) {
                    if (session == mySession) _state.value = _state.value.copy(playing = false)
                    return
                }
                val sr = dec.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                val track0 = AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, sr * 8 / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audio = track0
                track0.play()
                val clickNormal = click(sr, 1600.0)
                val clickDown = click(sr, 2600.0)
                val startPos = first.presentationUs / 1e6
                var chunk: StreamDecoder.Chunk? = first
                var lastUi = 0L
                while (chunk != null && session == mySession) {
                    val ch = dec.channels
                    val frames = chunk.samples.size / ch
                    val out = FloatArray(frames * 2)
                    for (i in 0 until frames) {
                        val l = chunk.samples[i * ch]
                        val r = if (ch > 1) chunk.samples[i * ch + 1] else l
                        out[2 * i] = l
                        out[2 * i + 1] = r
                    }
                    if (clicksOn && track.bpm != null && track.firstBeat != null) {
                        mixClicks(out, sr, chunk.presentationUs / 1e6, track, clickNormal, clickDown)
                    }
                    var off = 0
                    while (off < out.size && session == mySession) {
                        val w = track0.write(out, off, out.size - off, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) break
                        off += w
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 200 && session == mySession) {
                        lastUi = now
                        val pos = startPos + track0.playbackHeadPosition.toDouble() / sr
                        _state.value = _state.value.copy(positionSec = pos)
                    }
                    chunk = dec.read()
                }
                if (session == mySession) {
                    // Let the buffer drain at the natural end of the song.
                    Thread.sleep(300)
                }
            }
            if (session == mySession) _state.value = _state.value.copy(playing = false)
        } catch (e: Exception) {
            if (session == mySession) _state.value = _state.value.copy(playing = false, error = e.message)
        } finally {
            audio?.let {
                runCatching { it.pause(); it.flush(); it.stop() }
                it.release()
            }
        }
    }

    private fun mixClicks(out: FloatArray, sr: Int, chunkStart: Double, track: Track, normal: FloatArray, down: FloatArray) {
        val bpm = track.bpm!!
        val first = track.firstBeat!!
        val beat = 60.0 / bpm
        val frames = out.size / 2
        val chunkEnd = chunkStart + frames.toDouble() / sr
        val clickLen = normal.size.toDouble() / sr
        val i0 = ceil((chunkStart - clickLen - first) / beat).toInt().coerceAtLeast(0)
        val i1 = floor((chunkEnd - first) / beat).toInt()
        for (i in i0..i1) {
            val t = first + i * beat
            val c = if (((i - track.downbeatOffset) % 4 + 4) % 4 == 0) down else normal
            val startFrame = ((t - chunkStart) * sr).toInt()
            for (k in c.indices) {
                val f = startFrame + k
                if (f < 0) continue
                if (f >= frames) break
                out[2 * f] += c[k]
                out[2 * f + 1] += c[k]
            }
        }
    }

    private fun click(sr: Int, freq: Double): FloatArray =
        FloatArray((0.03 * sr).toInt()) { i ->
            val t = i.toDouble() / sr
            (0.45 * sin(2 * PI * freq * t) * exp(-t * 120)).toFloat()
        }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
