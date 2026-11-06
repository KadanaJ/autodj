package com.autodj.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.autodj.app.MainActivity
import com.autodj.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the DJ session alive with the screen off: foreground notification with controls,
 * a media session for lock screen / headphone buttons, and pause on headphone unplug.
 */
class DjService : Service() {
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) DjController.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "DJ playback", NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "AutoDJ").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = DjController.resume()
                override fun onPause() = DjController.pause()
                override fun onSkipToNext() {
                    DjController.skip()
                }
                override fun onStop() = DjController.stop()
            })
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        scope.launch {
            DjController.state
                .map { Triple(it.playing, it.engine.current?.id, it.active) }
                .distinctUntilChanged()
                .collect { update() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> DjController.togglePause()
            ACTION_SKIP -> DjController.skip()
            ACTION_STOP -> DjController.stop()
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private fun update() {
        val st = DjController.state.value
        if (!st.active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val t = st.engine.current
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title ?: "AutoDJ")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, ((t?.durationSec ?: 0.0) * 1000).toLong())
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP)
                .setState(
                    if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    (st.engine.positionSec * 1000).toLong(),
                    if (st.playing) 1f else 0f,
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(this, action.hashCode(), Intent(this, DjService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(): Notification {
        val st = DjController.state.value
        val t = st.engine.current
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_autodj)
            .setContentTitle(t?.title ?: "AutoDJ")
            .setContentText(t?.artist ?: "Starting…")
            .setContentIntent(open)
            .setOngoing(st.playing)
            .addAction(if (st.playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE) else action(R.drawable.ic_play, "Play", ACTION_TOGGLE))
            .addAction(action(R.drawable.ic_skip, "Mix to next", ACTION_SKIP))
            .addAction(action(R.drawable.ic_stop, "Stop", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "dj"
        private const val NOTIFICATION_ID = 7
        const val ACTION_TOGGLE = "com.autodj.TOGGLE"
        const val ACTION_SKIP = "com.autodj.SKIP"
        const val ACTION_STOP = "com.autodj.STOP"
    }
}
