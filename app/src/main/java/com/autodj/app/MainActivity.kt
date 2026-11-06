package com.autodj.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autodj.ui.AutoDjTheme
import com.autodj.ui.HomeScreen
import com.autodj.ui.LibraryFilter
import com.autodj.ui.LibraryScreen
import com.autodj.ui.MainViewModel
import com.autodj.ui.Palette
import com.autodj.ui.TrackSheet

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AutoDjTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
    }

    if (!hasPermission) {
        PermissionScreen {
            launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
        return
    }

    // Pick up new or changed songs every time the app opens.
    LaunchedEffect(Unit) { vm.startScan() }

    val tracks by vm.tracks.collectAsState()
    val scan by vm.scan.collectAsState()
    val player by vm.player.state.collectAsState()
    val busy by vm.busy.collectAsState()
    var libraryFilter by remember { mutableStateOf<LibraryFilter?>(null) }
    var openTrackId by remember { mutableStateOf<Long?>(null) }

    val filter = libraryFilter
    if (filter == null) {
        HomeScreen(
            tracks = tracks,
            scan = scan,
            onScan = vm::startScan,
            onOpenLibrary = { pace -> libraryFilter = pace?.let { LibraryFilter.ByPace(it) } ?: LibraryFilter.All },
        )
    } else {
        BackHandler { libraryFilter = null }
        LibraryScreen(
            tracks = tracks,
            initialFilter = filter,
            playingId = player.trackId.takeIf { player.playing },
            onBack = { libraryFilter = null },
            onOpenTrack = { openTrackId = it.id },
        )
    }

    val open = openTrackId?.let { id -> tracks.firstOrNull { it.id == id } }
    if (open != null) {
        TrackSheet(
            track = open,
            player = player,
            busy = open.id in busy,
            onDismiss = {
                openTrackId = null
                vm.player.stop()
            },
            onPlay = { vm.player.play(open, it) },
            onStop = vm.player::stop,
            onClicks = vm.player::setClicks,
            onReanalyze = { vm.reanalyze(open, it) },
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AutoDJ", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
        Spacer(Modifier.height(16.dp))
        Text(
            "AutoDJ needs access to the music files on your phone so it can measure each song's tempo and mix them. Nothing leaves your phone.",
            color = Palette.TextDim,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)) {
            Text("Allow access to music")
        }
    }
}
