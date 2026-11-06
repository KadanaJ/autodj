package com.autodj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodj.analysis.KeyDetector
import com.autodj.data.Track
import com.autodj.player.PreviewPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(
    track: Track,
    player: PreviewPlayer.State,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPlay: (startSec: Double) -> Unit,
    onStop: () -> Unit,
    onClicks: (Boolean) -> Unit,
    onReanalyze: (bpmHint: Double?) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isThis = player.trackId == track.id
    val playing = isThis && player.playing
    val duration = track.durationMs / 1000.0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.Surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 2)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(48.dp), color = Palette.AccentGlow)
                    Spacer(Modifier.width(16.dp))
                    Text("Re-analysing…", color = Palette.TextDim)
                } else {
                    val bpm = track.paceBpm
                    Text(
                        if (bpm == null) "—" else "%.1f".format(bpm).removeSuffix(".0"),
                        style = MaterialTheme.typography.displayLarge,
                        color = Palette.Text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("BPM", color = Palette.TextDim, modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            val (beatLabel, beatColor) = when {
                track.status == Track.STATUS_FAILED -> "Couldn't analyse: ${track.error}" to Palette.Warning
                track.status == Track.STATUS_SKIPPED -> "Skipped: ${track.error}" to Palette.TextFaint
                track.status == Track.STATUS_PENDING -> "Waiting for analysis" to Palette.TextFaint
                track.bpmConfidence >= 0.7 -> "Steady beat — great for beatmatching" to Palette.pace(com.autodj.data.Pace.CRUISE)
                track.hasSteadyBeat -> "Mostly steady beat" to Palette.pace(com.autodj.data.Pace.CRUISE)
                else -> "No steady beat — will be crossfaded, not beatmatched" to Palette.Warning
            }
            Text(beatLabel, color = beatColor, style = MaterialTheme.typography.bodyMedium)
            if (track.bpmHint != null) {
                Text("Tempo set by you", color = Palette.TextFaint, style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InfoTile("Key", track.keyIndex?.let { "${KeyDetector.name(it)} · ${KeyDetector.camelot(it)}" } ?: "—", Modifier.weight(1f))
                InfoTile("Energy", track.energy?.let { "${(it * 100).toInt()}" } ?: "—", Modifier.weight(1f)) {
                    track.energy?.let { EnergyDots(it) }
                }
                InfoTile("Pace", track.pace?.label ?: "—", Modifier.weight(1f), color = Palette.pace(track.pace))
            }

            // Preview
            Spacer(Modifier.height(22.dp))
            Text("LISTEN", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Palette.button)
                        .clickable(enabled = !busy) { if (playing) onStop() else onPlay(if (isThis) player.positionSec else duration * 0.3) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (playing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(2) { Box(Modifier.size(width = 6.dp, height = 20.dp).background(Color.White, RoundedCornerShape(2.dp))) }
                        }
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Beat clicks", color = Palette.Text, style = MaterialTheme.typography.titleMedium)
                    Text("Clicks should land on the kick drum", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                }
                Switch(
                    checked = player.clicks,
                    onCheckedChange = onClicks,
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent),
                )
            }
            if (duration > 0) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val pos = if (isThis) player.positionSec else duration * 0.3
                Slider(
                    value = dragging ?: (pos / duration).toFloat().coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onPlay(it * duration) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.AccentGlow),
                )
                Row {
                    Text(formatTime(dragging?.let { it * duration } ?: pos), color = Palette.TextFaint, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = Palette.TextFaint, style = MaterialTheme.typography.labelMedium)
                }
            }

            // Fixes
            if (track.status != Track.STATUS_SKIPPED && track.status != Track.STATUS_PENDING) {
                Spacer(Modifier.height(22.dp))
                Text("WRONG TEMPO?", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                Spacer(Modifier.height(8.dp))
                val bpm = track.bpm
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = !busy && bpm != null, onClick = { onReanalyze(track.paceBpm!! / 2) }) { Text("÷ 2") }
                    OutlinedButton(enabled = !busy && bpm != null, onClick = { onReanalyze(track.paceBpm!! * 2) }) { Text("× 2") }
                    if (track.bpmHint != null) {
                        TextButton(enabled = !busy, onClick = { onReanalyze(null) }) { Text("Reset to auto") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TapTempo(enabled = !busy, onApply = { onReanalyze(it) })
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier, color: Color = Palette.Text, extra: @Composable () -> Unit = {}) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.SurfaceHigh)
            .padding(12.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        extra()
    }
}

/** Tap along to the beat; the median interval becomes a hint for re-analysis. */
@Composable
private fun TapTempo(enabled: Boolean, onApply: (Double) -> Unit) {
    val taps = remember { mutableStateListOf<Long>() }
    val tapped: Double? = if (taps.size >= 4) {
        val gaps = taps.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        60_000.0 / gaps[gaps.size / 2]
    } else {
        null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 150.dp, height = 64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Outline, RoundedCornerShape(18.dp))
                .clickable(enabled = enabled) {
                    val now = System.currentTimeMillis()
                    // A pause of more than 2 s starts a new measurement.
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now)
                    if (taps.size > 16) taps.removeAt(0)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (tapped != null) "%.0f".format(tapped) else "TAP",
                color = Palette.Text,
                fontSize = if (tapped != null) 26.sp else 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (tapped == null) "Play the song and tap along to the beat (${maxOf(0, 4 - taps.size)} more taps)" else "Tapped tempo",
                color = Palette.TextDim,
                style = MaterialTheme.typography.labelMedium,
            )
            if (tapped != null) {
                Row {
                    Button(
                        enabled = enabled,
                        onClick = {
                            onApply(tapped)
                            taps.clear()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                    ) { Text("Use") }
                    IconButtonSmall { taps.clear() }
                }
            }
        }
    }
}

@Composable
private fun IconButtonSmall(onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(Icons.Filled.Close, contentDescription = "Clear taps", tint = Palette.TextDim)
    }
}

private fun formatTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
