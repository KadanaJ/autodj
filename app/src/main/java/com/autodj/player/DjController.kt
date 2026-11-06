package com.autodj.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.autodj.data.LibraryRepository
import com.autodj.engine.EngineStatus
import com.autodj.engine.MixEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DjState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val engine: EngineStatus = EngineStatus(),
    val error: String? = null,
)

/** Owns the mixing engine and the audio output thread. One per app process. */
object DjController {
    const val SAMPLE_RATE = 44100

    private val _state = MutableStateFlow(DjState())
    val state: StateFlow<DjState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: MixEngine? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private lateinit var app: Context
    private var focus: AudioFocusRequest? = null

    /** Starts a DJ session from [startId] (or a random steady song). */
    fun start(context: Context, startId: Long?) {
        app = context.applicationContext
        scope.launch {
            val tracks = LibraryRepository.get(app).loadTrackInfos()
            val steady = tracks.filter { it.beatable }
            val first = tracks.firstOrNull { it.id == startId } ?: steady.randomOrNull() ?: tracks.randomOrNull()
            if (first == null) {
                _state.value = DjState(error = "No analysed songs yet")
                return@launch
            }
            stopInternal()
            val e = MixEngine(SAMPLE_RATE, LibraryFeed(app, steady.ifEmpty { tracks }, SAMPLE_RATE))
            engine = e
            e.play(first)
            paused = false
            requestFocus()
            startAudioThread(e)
            _state.value = DjState(active = true, playing = true)
            app.startForegroundService(Intent(app, DjService::class.java))
        }
    }

    fun togglePause() = if (paused) resume() else pause()

    fun pause() {
        if (engine == null) return
        paused = true
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        if (engine == null) return
        requestFocus()
        paused = false
        _state.value = _state.value.copy(playing = true)
    }

    fun skip() = engine?.skip()

    fun jumpToMix() = engine?.jumpToMix()

    fun stop() {
        stopInternal()
        _state.value = DjState()
        if (::app.isInitialized) app.stopService(Intent(app, DjService::class.java))
    }

    private fun stopInternal() {
        running = false
        thread?.join(1000)
        thread = null
        engine = null
        focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }

    private fun requestFocus() {
        val am = app.getSystemService(AudioManager::class.java)
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> scope.launch { pause() }
                }
            }
            .build().also { focus = it }
        am.requestAudioFocus(req)
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun startAudioThread(e: MixEngine) {
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val block = 1024
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, block * 8 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val buf = FloatArray(block * 2)
            var lastUi = 0L
            try {
                track.play()
                while (running) {
                    if (paused) {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                        Thread.sleep(30)
                        continue
                    }
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    e.render(buf, block)
                    track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 150) {
                        lastUi = now
                        val st = e.status
                        _state.value = _state.value.copy(engine = st)
                        if (e.isIdle) {
                            scope.launch { stop() }
                            break
                        }
                    }
                }
            } catch (ex: Exception) {
                _state.value = _state.value.copy(error = ex.message)
            } finally {
                runCatching { track.stop() }
                track.release()
                e.release()
            }
        }, "dj-audio").apply { start() }
    }
}
