package com.autodj.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.MediaStore
import com.autodj.analysis.TrackAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks(
              id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              artist TEXT NOT NULL,
              album TEXT NOT NULL,
              duration_ms INTEGER NOT NULL,
              date_modified INTEGER NOT NULL,
              size INTEGER NOT NULL,
              status INTEGER NOT NULL DEFAULT 0,
              error TEXT,
              analysis_version INTEGER NOT NULL DEFAULT 0,
              bpm REAL,
              bpm_confidence REAL NOT NULL DEFAULT 0,
              first_beat REAL,
              downbeat_offset INTEGER NOT NULL DEFAULT 0,
              key_index INTEGER,
              key_confidence REAL NOT NULL DEFAULT 0,
              energy REAL,
              loudness_db REAL,
              onset_rate REAL,
              brightness REAL,
              bpm_hint REAL,
              beat_energy BLOB
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** Local catalogue of the phone's music files and their analysis results. */
class LibraryRepository private constructor(private val context: Context) {
    private val db = LibraryDb(context)
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    suspend fun refresh() = withContext(Dispatchers.IO) { _tracks.value = loadAll() }

    private fun loadAll(): List<Track> {
        val out = ArrayList<Track>()
        db.readableDatabase.rawQuery(
            "SELECT id,title,artist,album,duration_ms,status,error,bpm,bpm_confidence,first_beat,downbeat_offset," +
                "key_index,key_confidence,energy,loudness_db,bpm_hint FROM tracks ORDER BY title COLLATE NOCASE",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Track(
                    id = c.getLong(0),
                    title = c.getString(1),
                    artist = c.getString(2),
                    album = c.getString(3),
                    durationMs = c.getLong(4),
                    status = c.getInt(5),
                    error = c.getString(6),
                    bpm = c.doubleOrNull(7),
                    bpmConfidence = c.getDouble(8),
                    firstBeat = c.doubleOrNull(9),
                    downbeatOffset = c.getInt(10),
                    keyIndex = if (c.isNull(11)) null else c.getInt(11),
                    keyConfidence = c.getDouble(12),
                    energy = c.doubleOrNull(13),
                    loudnessDb = c.doubleOrNull(14),
                    bpmHint = c.doubleOrNull(15),
                )
            }
        }
        return out
    }

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    /** Mirrors MediaStore's music files into the database. Returns the number of songs found. */
    suspend fun syncWithMediaStore(): Int = withContext(Dispatchers.IO) {
        data class Row(val id: Long, val title: String, val artist: String, val album: String, val duration: Long, val modified: Long, val size: Long)

        val rows = ArrayList<Row>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += Row(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "Unknown title",
                    artist = c.getString(2)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                    album = c.getString(3) ?: "",
                    duration = c.getLong(4),
                    modified = c.getLong(5),
                    size = c.getLong(6),
                )
            }
        }

        val w = db.writableDatabase
        val existing = HashMap<Long, Pair<Long, Long>>()
        w.rawQuery("SELECT id,date_modified,size FROM tracks", null).use { c ->
            while (c.moveToNext()) existing[c.getLong(0)] = c.getLong(1) to c.getLong(2)
        }
        w.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("title", r.title)
                    put("artist", r.artist)
                    put("album", r.album)
                    put("duration_ms", r.duration)
                    put("date_modified", r.modified)
                    put("size", r.size)
                }
                val old = existing.remove(r.id)
                if (old == null) {
                    v.put("id", r.id)
                    w.insert("tracks", null, v)
                } else {
                    if (old.first != r.modified || old.second != r.size) {
                        // File changed: analyse it again.
                        v.put("status", Track.STATUS_PENDING)
                    }
                    w.update("tracks", v, "id=?", arrayOf(r.id.toString()))
                }
            }
            for (gone in existing.keys) w.delete("tracks", "id=?", arrayOf(gone.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        rows.size
    }

    /** Tracks that need (re-)analysis. */
    suspend fun pendingTracks(): List<Track> = withContext(Dispatchers.IO) {
        val ids = HashSet<Long>()
        db.readableDatabase.rawQuery(
            "SELECT id FROM tracks WHERE status=? OR (status=? AND analysis_version<?)",
            arrayOf(Track.STATUS_PENDING.toString(), Track.STATUS_DONE.toString(), TrackAnalysis.VERSION.toString()),
        ).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        loadAll().filter { it.id in ids }
    }

    suspend fun saveAnalysis(id: Long, a: TrackAnalysis, bpmHint: Double?) = withContext(Dispatchers.IO) {
        val g = a.grid
        val v = ContentValues().apply {
            put("status", Track.STATUS_DONE)
            putNull("error")
            put("analysis_version", TrackAnalysis.VERSION)
            if (g != null) {
                put("bpm", g.bpm)
                put("bpm_confidence", g.confidence)
                put("first_beat", g.firstBeat)
                put("downbeat_offset", g.downbeatOffset)
            } else {
                putNull("bpm")
                put("bpm_confidence", 0.0)
                putNull("first_beat")
            }
            if (a.keyIndex != null) put("key_index", a.keyIndex) else putNull("key_index")
            put("key_confidence", a.keyConfidence)
            put("energy", a.energy)
            put("loudness_db", a.loudnessDb)
            put("onset_rate", a.onsetRate)
            put("brightness", a.brightness)
            if (bpmHint != null) put("bpm_hint", bpmHint) else putNull("bpm_hint")
            val bb = ByteBuffer.allocate(a.beatEnergy.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(a.beatEnergy)
            put("beat_energy", bb.array())
        }
        db.writableDatabase.update("tracks", v, "id=?", arrayOf(id.toString()))
    }

    suspend fun saveProblem(id: Long, status: Int, message: String) = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("status", status)
            put("error", message)
            put("analysis_version", TrackAnalysis.VERSION)
        }
        db.writableDatabase.update("tracks", v, "id=?", arrayOf(id.toString()))
    }

    /** Engine-ready descriptions (with per-beat energy) of all analysed songs. */
    suspend fun loadTrackInfos(): List<com.autodj.engine.TrackInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<com.autodj.engine.TrackInfo>()
        db.readableDatabase.rawQuery(
            "SELECT id,title,artist,duration_ms,bpm,first_beat,downbeat_offset,bpm_confidence,loudness_db,beat_energy,key_index,energy " +
                "FROM tracks WHERE status=?",
            arrayOf(Track.STATUS_DONE.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val blob = if (c.isNull(9)) ByteArray(0) else c.getBlob(9)
                val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val energy = FloatArray(fb.remaining()).also { fb.get(it) }
                out += com.autodj.engine.TrackInfo(
                    id = c.getLong(0),
                    title = c.getString(1),
                    artist = c.getString(2),
                    durationSec = c.getLong(3) / 1000.0,
                    bpm = c.doubleOrNull(4),
                    firstBeat = c.doubleOrNull(5),
                    downbeatOffset = c.getInt(6),
                    beatConfidence = c.getDouble(7),
                    loudnessDb = c.doubleOrNull(8),
                    blockEnergy = energy,
                    keyIndex = if (c.isNull(10)) null else c.getInt(10),
                    energy = c.doubleOrNull(11),
                )
            }
        }
        out
    }

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext).also { instance = it }
            }
    }
}
