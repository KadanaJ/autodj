package com.autodj.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodj.data.Pace
import com.autodj.data.Track

@Composable
fun HomeScreen(
    tracks: List<Track>,
    scan: ScanInfo,
    onScan: () -> Unit,
    onOpenLibrary: (Pace?) -> Unit,
) {
    val analyzed = tracks.count { it.isAnalyzed }
    val steady = tracks.count { it.hasSteadyBeat }
    val loose = tracks.count { it.isAnalyzed && !it.hasSteadyBeat }
    val failed = tracks.count { it.status == Track.STATUS_FAILED }
    val skipped = tracks.count { it.status == Track.STATUS_SKIPPED }
    val byPace = tracks.groupingBy { it.pace }.eachCount()

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AutoDJ", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onOpenLibrary(null) }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Library", tint = Palette.Text)
                }
            }

            Spacer(Modifier.weight(1f))

            BigButton(
                scan = scan,
                hasLibrary = tracks.isNotEmpty() && analyzed > 0,
                onClick = { if (scan.running) Unit else if (analyzed == 0) onScan() else onOpenLibrary(null) },
            )
            Spacer(Modifier.height(24.dp))
            val caption = when {
                scan.running && scan.total > 0 -> "Finding the BPM, key and energy of every song.\nYou can leave the app — it keeps going."
                scan.running -> "Looking for music on your phone…"
                tracks.isEmpty() -> "Tap to find the music on your phone"
                else -> "$steady songs with a steady beat · tap to browse"
            }
            Text(caption, color = Palette.TextDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            if (!scan.running && tracks.isNotEmpty()) {
                TextButton(onClick = onScan) { Text("Rescan library", color = Palette.AccentGlow) }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "PACE",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )
            val rows = Pace.entries.chunked(2)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { p ->
                        PaceCard(p, byPace[p] ?: 0, Modifier.weight(1f)) { onOpenLibrary(p) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val stats = buildList {
                if (tracks.isNotEmpty()) add("${tracks.size} songs")
                if (loose > 0) add("$loose no steady beat")
                if (skipped > 0) add("$skipped skipped")
                if (failed > 0) add("$failed failed")
            }.joinToString(" · ")
            Text(
                stats,
                color = Palette.TextFaint,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun BigButton(scan: ScanInfo, hasLibrary: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    val breathe by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (scan.running) 1.04f else 1.015f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "breathe",
    )
    val fraction = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
    val progress by animateFloatAsState(fraction, tween(600), label = "progress")
    val size = 230.dp

    Box(Modifier.size(size + 90.dp), contentAlignment = Alignment.Center) {
        // Expanding rings, Shazam-style.
        Canvas(Modifier.fillMaxSize()) {
            val base = size.toPx() / 2
            val extra = this.size.minDimension / 2 - base
            for (k in 0 until 2) {
                val p = (ring + k * 0.5f) % 1f
                drawCircle(
                    color = Palette.AccentGlow.copy(alpha = (1 - p) * if (scan.running) 0.35f else 0.14f),
                    radius = base + extra * p,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(size)
                .scale(breathe)
                .clip(CircleShape)
                .background(Palette.button)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (scan.running && scan.total > 0) {
                Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    val stroke = 6.dp.toPx()
                    drawArc(
                        color = Color.White.copy(alpha = 0.18f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(stroke),
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        topLeft = Offset.Zero, size = Size(this.size.width, this.size.height),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    scan.running && scan.total > 0 -> {
                        Text("${(fraction * 100).toInt()}%", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                        Text("${scan.done} / ${scan.total}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                    }
                    scan.running -> {
                        Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Text("Scanning", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    hasLibrary -> {
                        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Text("Library ready", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    else -> {
                        Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Text("Scan music", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaceCard(pace: Pace, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val color = Palette.pace(pace)
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), Palette.Surface)))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(pace.label, color = Palette.Text, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(pace.rangeLabel, color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        Text("$count songs", color = color, style = MaterialTheme.typography.labelLarge)
    }
}
