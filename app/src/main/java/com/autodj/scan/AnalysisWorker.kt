package com.autodj.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autodj.analysis.TrackAnalyzer
import com.autodj.app.R
import com.autodj.audio.AnalysisDecoder
import com.autodj.data.LibraryRepository
import com.autodj.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

object TrackAnalysisRunner {
    private const val MIN_MS = 45_000L
    private const val MAX_MS = 20 * 60_000L

    /** Decodes and analyses one track, storing the result. */
    suspend fun run(context: Context, track: Track, bpmHint: Double? = track.bpmHint) {
        val repo = LibraryRepository.get(context)
        if (track.durationMs in 1 until MIN_MS) {
            repo.saveProblem(track.id, Track.STATUS_SKIPPED, "Shorter than 45 seconds")
            return
        }
        if (track.durationMs > MAX_MS) {
            repo.saveProblem(track.id, Track.STATUS_SKIPPED, "Longer than 20 minutes (mix or podcast?)")
            return
        }
        try {
            val ctx = coroutineContext
            val mono = withContext(Dispatchers.Default) {
                AnalysisDecoder.decode(context, track.uri, MAX_MS / 1000.0) { !ctx.isActive }
            }
            val result = withContext(Dispatchers.Default) {
                TrackAnalyzer.analyze(mono.samples, mono.sampleRate, bpmHint)
            }
            repo.saveAnalysis(track.id, result, bpmHint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            repo.saveProblem(track.id, Track.STATUS_FAILED, "Not enough memory to analyse")
        } catch (e: Exception) {
            repo.saveProblem(track.id, Track.STATUS_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }
}

/** Finds new or changed music files and analyses them in the background. */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = LibraryRepository.get(applicationContext)
        repo.syncWithMediaStore()
        repo.refresh()
        val todo = repo.pendingTracks()
        if (todo.isEmpty()) return Result.success()

        val total = todo.size
        val done = AtomicInteger(0)
        runCatching { setForeground(foregroundInfo(0, total)) }
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to total))

        val queue = Channel<Track>(Channel.UNLIMITED)
        todo.forEach { queue.trySend(it) }
        queue.close()

        coroutineScope {
            val ticker = launch {
                var last = -1
                while (isActive) {
                    delay(1500)
                    val d = done.get()
                    if (d != last) {
                        last = d
                        repo.refresh()
                        setProgress(workDataOf(KEY_DONE to d, KEY_TOTAL to total))
                        runCatching { setForeground(foregroundInfo(d, total)) }
                    }
                }
            }
            val workers = List(PARALLEL) {
                launch(Dispatchers.Default) {
                    for (t in queue) {
                        ensureActive()
                        TrackAnalysisRunner.run(applicationContext, t)
                        done.incrementAndGet()
                    }
                }
            }
            workers.forEach { it.join() }
            ticker.cancel()
        }
        repo.refresh()
        setProgress(workDataOf(KEY_DONE to total, KEY_TOTAL to total))
        return Result.success()
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Library analysis", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_autodj)
            .setContentTitle("Analysing your music")
            .setContentText("$done of $total songs")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val UNIQUE_NAME = "library-scan"
        private const val CHANNEL = "analysis"
        private const val NOTIFICATION_ID = 41
        private val PARALLEL = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AnalysisWorker>().build(),
            )
        }
    }
}
