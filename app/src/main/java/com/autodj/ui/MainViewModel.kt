package com.autodj.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.autodj.data.LibraryRepository
import com.autodj.data.Track
import com.autodj.player.PreviewPlayer
import com.autodj.scan.AnalysisWorker
import com.autodj.scan.TrackAnalysisRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanInfo(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LibraryRepository.get(app)
    val tracks: StateFlow<List<Track>> = repo.tracks
    val player = PreviewPlayer(app)

    val scan: StateFlow<ScanInfo> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(AnalysisWorker.UNIQUE_NAME)
        .map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished }
            if (info == null) {
                ScanInfo()
            } else {
                ScanInfo(
                    running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                    done = info.progress.getInt(AnalysisWorker.KEY_DONE, 0),
                    total = info.progress.getInt(AnalysisWorker.KEY_TOTAL, 0),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanInfo())

    /** Ids of tracks currently being re-analysed from the detail sheet. */
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    val busy: StateFlow<Set<Long>> = _busy

    init {
        viewModelScope.launch { repo.refresh() }
        // Reload once a scan finishes so the final results show up.
        viewModelScope.launch {
            var wasRunning = false
            scan.collect {
                if (wasRunning && !it.running) repo.refresh()
                wasRunning = it.running
            }
        }
    }

    fun startScan() = AnalysisWorker.start(getApplication())

    /** Re-analyse with a user tempo hint (null = back to automatic). */
    fun reanalyze(track: Track, bpmHint: Double?) {
        if (track.id in _busy.value) return
        _busy.value = _busy.value + track.id
        if (player.state.value.trackId == track.id) player.stop()
        viewModelScope.launch {
            try {
                TrackAnalysisRunner.run(getApplication(), track, bpmHint)
                repo.refresh()
            } finally {
                _busy.value = _busy.value - track.id
            }
        }
    }

    override fun onCleared() {
        player.stop()
    }
}
