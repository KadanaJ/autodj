package com.autodj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodj.analysis.KeyDetector
import com.autodj.engine.TransitionType
import com.autodj.player.DjState

@Composable
fun DjScreen(
    state: DjState,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onJumpToMix: () -> Unit,
    onStop: () -> Unit,
) {
    val e = state.engine
    val cur = e.current
    val plan = e.plan
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Text) }
            Text("Now mixing", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop", color = Palette.TextDim) }
        }
        Spacer(Modifier.weight(0.6f))

        // Tempo dial
        val bpm = e.tempoBpm
        Box(
            Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.35f), Palette.Surface)))
                .border(2.dp, Palette.AccentGlow.copy(alpha = if (e.blend >= 0) 0.9f else 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(bpm?.let { "%.1f".format(it) } ?: "—", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                Text("BPM", color = Palette.TextDim, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(cur?.title ?: "Starting…", style = MaterialTheme.typography.headlineMedium, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(cur?.artist ?: "", style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        if (cur != null) {
            val frac = (e.positionSec / cur.durationSec).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Outline)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Palette.AccentGlow))
                plan?.let {
                    val m = (it.aStart / cur.durationSec).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(m).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.Warning))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(time(e.positionSec), color = Palette.TextFaint, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(time(cur.durationSec), color = Palette.TextFaint, style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(18.dp))
        // Transition status
        val statusText = when {
            e.blend >= 0 && plan?.type == TransitionType.BEATMATCH -> "Beatmatching · ${(e.blend * 100).toInt()}%"
            e.blend >= 0 -> "Crossfading · ${(e.blend * 100).toInt()}%"
            plan != null -> {
                val inSec = plan.aStart - e.positionSec
                val kind = if (plan.type == TransitionType.BEATMATCH) "Beatmatched mix" else "Crossfade"
                "$kind in ${time(inSec.coerceAtLeast(0.0))}"
            }
            else -> "Last song"
        }
        Text(statusText, color = if (e.blend >= 0) Palette.AccentGlow else Palette.TextDim, style = MaterialTheme.typography.titleMedium)

        val next = e.next
        if (next != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Surface)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                    Text(next.title, color = Palette.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(next.artist, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(next.bpm?.let { "%.0f".format(it) } ?: "—", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(next.keyIndex?.let { KeyDetector.camelot(it) } ?: "", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            TextButton(onClick = onJumpToMix, enabled = plan != null && e.blend < 0) {
                Text("Hear\nnext mix", textAlign = TextAlign.Center, color = Palette.TextDim)
            }
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Palette.button).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (state.playing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(2) { Box(Modifier.size(width = 8.dp, height = 28.dp).background(Color.White, RoundedCornerShape(2.dp))) }
                    }
                } else {
                    Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
            TextButton(onClick = onSkip, enabled = next != null && e.blend < 0) {
                Text("Mix\nnow", textAlign = TextAlign.Center, color = Palette.TextDim)
            }
        }
        if (e.underruns > 0 || e.alignmentMs != 0.0) {
            Text(
                "beat fine-tune %.0f ms%s".format(e.alignmentMs, if (e.underruns > 0) " · ${e.underruns} dropouts" else ""),
                color = Palette.TextFaint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        state.error?.let { Text(it, color = Palette.Warning, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}

private fun time(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
