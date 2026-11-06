package com.autodj.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val status: Int,
    val error: String?,
    val bpm: Double?,
    val bpmConfidence: Double,
    val firstBeat: Double?,
    val downbeatOffset: Int,
    val keyIndex: Int?,
    val keyConfidence: Double,
    val energy: Double?,
    val loudnessDb: Double?,
    /** Tempo the user tapped; analysis fits the grid around it. */
    val bpmHint: Double?,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)

    val isAnalyzed: Boolean get() = status == STATUS_DONE

    /** Steady enough to beatmatch. Others get a plain crossfade. */
    val hasSteadyBeat: Boolean get() = isAnalyzed && bpm != null && bpmConfidence >= STEADY_CONFIDENCE

    /**
     * Tempo as it feels for choosing a pace. Very slow readings of energetic tracks are
     * usually half-time (a 68 BPM reading of a 136 BPM track).
     */
    val paceBpm: Double?
        get() {
            val b = bpm ?: return null
            if (bpmHint != null) return b
            return when {
                b < 70 && (energy ?: 0.0) > 0.45 -> b * 2
                b > 180 -> b / 2
                else -> b
            }
        }

    val pace: Pace? get() = if (hasSteadyBeat) paceBpm?.let { Pace.of(it) } else null

    val needsCheck: Boolean get() = status == STATUS_FAILED || (isAnalyzed && bpmConfidence < STEADY_CONFIDENCE)

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DONE = 1
        const val STATUS_FAILED = 2
        const val STATUS_SKIPPED = 3
        const val STEADY_CONFIDENCE = 0.35
    }
}

enum class Pace(val label: String, val minBpm: Double, val maxBpm: Double, val target: Int, val description: String) {
    CHILL("Chill", 60.0, 100.0, 88, "Relaxed evenings"),
    CRUISE("Cruise", 100.0, 118.0, 110, "Easy-going"),
    WORKOUT("Workout", 118.0, 135.0, 128, "Gym & running"),
    PEAK("Peak", 135.0, 200.0, 140, "Full energy");

    val rangeLabel: String
        get() = when (this) {
            CHILL -> "under 100 BPM"
            PEAK -> "135+ BPM"
            else -> "${minBpm.toInt()}–${maxBpm.toInt()} BPM"
        }

    companion object {
        fun of(bpm: Double): Pace = entries.firstOrNull { bpm >= it.minBpm && bpm < it.maxBpm }
            ?: if (bpm < CHILL.minBpm) CHILL else PEAK
    }
}
