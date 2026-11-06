package com.autodj.player

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.autodj.engine.PcmSource
import com.autodj.engine.TrackFeed
import com.autodj.engine.TrackInfo
import com.autodj.engine.TransitionPlanner
import kotlin.math.abs
import kotlin.random.Random

/**
 * Step-2 song chooser: after each song, pick an unplayed one that can be beatmatched
 * (closest tempo first, with a little randomness). Step 3 replaces this with pace + shuffle.
 */
class LibraryFeed(
    private val context: Context,
    private val tracks: List<TrackInfo>,
    private val sampleRate: Int,
) : TrackFeed {
    private val played = HashSet<Long>()

    override fun next(current: TrackInfo): TrackInfo? {
        played += current.id
        var pool = tracks.filter { it.id !in played }
        if (pool.isEmpty()) {
            played.clear()
            played += current.id
            pool = tracks.filter { it.id != current.id }
        }
        if (pool.isEmpty()) return null
        val matchable = pool.filter { TransitionPlanner.beatRatio(current, it) != null }
        val candidates = (matchable.ifEmpty { pool }).sortedBy { tempoDistance(current, it) }
        return candidates.take(3).random(Random)
    }

    private fun tempoDistance(a: TrackInfo, b: TrackInfo): Double {
        val r = TransitionPlanner.beatRatio(a, b)?.second ?: return 10.0 + abs((a.bpm ?: 0.0) - (b.bpm ?: 0.0))
        return abs(r - 1)
    }

    override fun open(track: TrackInfo): PcmSource = DecoderSource(
        context,
        ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), track.id),
        sampleRate,
    )
}
