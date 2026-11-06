package com.autodj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodj.analysis.KeyDetector
import com.autodj.data.Pace
import com.autodj.data.Track

/** Library filter: a pace, everything, or tracks that need a look. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object NeedsCheck : LibraryFilter
    data class ByPace(val pace: Pace) : LibraryFilter
}

private enum class Sort(val label: String) { BPM("BPM"), ENERGY("Energy"), TITLE("Title") }

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    initialFilter: LibraryFilter,
    playingId: Long?,
    onBack: () -> Unit,
    onOpenTrack: (Track) -> Unit,
) {
    var filter by remember { mutableStateOf(initialFilter) }
    var sort by rememberSaveable { mutableStateOf(Sort.BPM) }
    var query by rememberSaveable { mutableStateOf("") }

    val shown = remember(tracks, filter, sort, query) {
        val q = query.trim().lowercase()
        tracks
            .asSequence()
            .filter {
                when (val f = filter) {
                    LibraryFilter.All -> true
                    LibraryFilter.NeedsCheck -> it.needsCheck
                    is LibraryFilter.ByPace -> it.pace == f.pace
                }
            }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
            .let { s ->
                when (sort) {
                    Sort.BPM -> s.sortedWith(compareBy(nullsLast()) { it.paceBpm })
                    Sort.ENERGY -> s.sortedWith(compareByDescending(nullsFirst()) { it.energy })
                    Sort.TITLE -> s.sortedBy { it.title.lowercase() }
                }
            }
            .toList()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .statusBarsPadding(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Text)
            }
            Text("Library", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            Text("${shown.size} songs", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search songs or artists") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Palette.Surface,
                unfocusedContainerColor = Palette.Surface,
                unfocusedBorderColor = Palette.Outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        val filters = listOf(LibraryFilter.All) + Pace.entries.map { LibraryFilter.ByPace(it) } + LibraryFilter.NeedsCheck
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters) { f ->
                val label = when (f) {
                    LibraryFilter.All -> "All"
                    LibraryFilter.NeedsCheck -> "Needs check"
                    is LibraryFilter.ByPace -> f.pace.label
                }
                val color = if (f is LibraryFilter.ByPace) Palette.pace(f.pace) else Palette.Accent
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(label) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.22f),
                        selectedLabelColor = Palette.Text,
                        labelColor = Palette.TextDim,
                    ),
                )
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort", color = Palette.TextFaint, style = MaterialTheme.typography.labelMedium)
            Sort.entries.forEach { s ->
                Text(
                    s.label,
                    color = if (sort == s) Palette.AccentGlow else Palette.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sort = s }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No songs yet — run a scan from the home screen.", color = Palette.TextDim)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                items(shown, key = { it.id }) { t ->
                    TrackRow(t, playing = t.id == playingId) { onOpenTrack(t) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (playing) Palette.Accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Palette.pace(t.pace)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Palette.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append(t.artist)
                t.keyIndex?.let { append(" · ").append(KeyDetector.camelot(it)) }
            }
            Text(sub, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        when (t.status) {
            Track.STATUS_PENDING -> Text("…", color = Palette.TextFaint, fontSize = 20.sp)
            Track.STATUS_FAILED, Track.STATUS_SKIPPED ->
                Icon(Icons.Filled.Warning, contentDescription = t.error, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
            else -> Column(horizontalAlignment = Alignment.End) {
                val bpm = t.paceBpm
                Text(
                    if (bpm == null) "—" else (if (t.hasSteadyBeat) "" else "~") + bpm.toInt(),
                    color = if (t.hasSteadyBeat) Palette.Text else Palette.TextFaint,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                EnergyDots(t.energy ?: 0.0)
            }
        }
    }
}

@Composable
fun EnergyDots(energy: Double, modifier: Modifier = Modifier) {
    val lit = (energy * 5 + 0.5).toInt().coerceIn(1, 5)
    Row(modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 5) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (i < lit) Palette.AccentGlow else Palette.Outline),
            )
        }
    }
}
