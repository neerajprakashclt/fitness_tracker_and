package app.fitness.data

import androidx.room.withTransaction
import app.fitness.core.ActivityLookup
import app.fitness.core.Common
import app.fitness.core.ImportDecision
import app.fitness.core.ImportFailed
import app.fitness.core.ImportPlanner
import app.fitness.core.Importer
import app.fitness.core.MatchMode
import app.fitness.core.ParsedActivity
import app.fitness.core.Routes
import app.fitness.core.SimilarCandidate
import app.fitness.core.SimilarKind
import app.fitness.core.SimilarMatch
import app.fitness.core.StreamKeys
import app.fitness.core.StravaCsv
import app.fitness.core.StreamCodec
import app.fitness.core.TerrainElevation
import app.fitness.core.TerrainException
import app.fitness.core.Times
import app.fitness.core.UntypedSport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

/** What importing one file did. */
sealed interface ImportOutcome {
    /** [kept] is a same-day lookalike that was judged a separate run, for the report. */
    data class Imported(val fileHash: String, val sport: String, val kept: SimilarMatch? = null, val note: String? = null) : ImportOutcome
    /** The same file (by content) is already in the database. */
    data class Duplicate(val fileHash: String) : ImportOutcome
    /** The same run as a stored activity, from another source (see `Similar`). Not imported. */
    data class SameRun(val existingHash: String, val kind: SimilarKind, val offsetSeconds: Double) : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

/** A single entry of the activity feed. */
class ActivityListItem(
    val fileHash: String,
    val sport: String,
    val startTime: Instant,
    val duration: Double?,
    val distance: Double?,
    val elevationGain: Double?,
    val device: String?,
    /** Encoded polylines, one per continuous stretch of track; empty without GPS. */
    val route: List<String>,
) {
    /** False for indoor and strength activities, which have no distance to show. */
    val hasDistance: Boolean get() = distance != null && distance > 0

    /** Average moving speed in m/s. */
    val avgSpeed: Double? get() = if (distance != null && duration != null && duration > 0) distance / duration else null
}

class LapItem(
    val index: Int,
    val duration: Double?,
    val distance: Double?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val avgPower: Double?,
    val avgSpeed: Double?,
    val elevationGain: Double?,
) {
    /** Average speed in m/s: the device's value, else distance over time. */
    val speed: Double? get() = avgSpeed ?: if (distance != null && duration != null && duration > 0) distance / duration else null
}

class ActivityDetail(val activity: ActivityListItem, val laps: List<LapItem>, val importedAt: String)

/** What a rebuild from the archive did. */
class RebuildResult(val rebuilt: Int, val skippedDeleted: Int, val failed: List<String>)

/** Disk used by the app's own data, in bytes. */
class StorageUsage(val archive: Long, val streams: Long, val database: Long) {
    val total get() = archive + streams + database
}

/**
 * Where activities come from and go to. Raw files are archived in `filesDir/imports/` so the database can always be
 * rebuilt from them (and are kept when an activity is deleted, as on the PC); streams are gzipped JSON in
 * `filesDir/streams/<file_hash>.json.gz`. Everything here runs off the main thread.
 */
class ActivityRepository(private val db: FitnessDatabase, private val filesDir: File) {
    private val dao = db.activities()
    private val importsDir = File(filesDir, "imports").apply { mkdirs() }
    private val streamsDir = File(filesDir, "streams").apply { mkdirs() }
    private val json = Json

    /** Looks up ground height for new files that have a track but no altitude. Null (the default) means never: no network. */
    @Volatile var terrain: TerrainService? = null

    internal fun dao() = dao

    /** Called after activities are deleted, so derived data that sums over all activities (the load series) can catch up. */
    @Volatile var afterDelete: (suspend () -> Unit)? = null
    internal fun archiveFiles(): List<File> = importsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    private val lookup = object : ActivityLookup {
        override suspend fun exists(fileHash: String) = dao.exists(fileHash)
        override suspend fun candidates(sport: String, anySport: Boolean, fromDate: String, toDate: String): List<SimilarCandidate> =
            dao.candidates(sport, anySport, fromDate, toDate).mapNotNull { r ->
                Times.parse(r.startTime)?.let { SimilarCandidate(r.fileHash, it, r.distance) }
            }
    }

    suspend fun import(
        filename: String,
        data: ByteArray,
        untyped: UntypedSport = UntypedSport.UNKNOWN,
        match: MatchMode = MatchMode.MOMENT,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            when (val d = ImportPlanner.plan(filename, data, lookup, untyped, match)) {
                is ImportDecision.Duplicate -> ImportOutcome.Duplicate(d.fileHash)
                is ImportDecision.SameRun -> ImportOutcome.SameRun(d.match.fileHash, d.match.kind, d.match.offsetSeconds)
                is ImportDecision.Import -> {
                    val (activity, note) = withElevation(d.fileHash, d.activity, allowNetwork = true)
                    store(d.fileHash, filename, data, activity, d.sport)
                    ImportOutcome.Imported(d.fileHash, d.sport, d.kept, note)
                }
            }
        } catch (e: ImportFailed) {
            ImportOutcome.Failed(e.message ?: "Import failed")
        }
    }

    /**
     * Climb from outside the file, for an activity that has none of its own (the file's altitude always wins, as on the PC):
     * terrain data already stored for it, else a Strava CSV total, else, when [allowNetwork] and the option is on, a look-up
     * that is also stored. A look-up that fails never fails the import: the activity is kept as parsed and the note says why.
     */
    private suspend fun withElevation(hash: String, a: ParsedActivity, allowNetwork: Boolean): Pair<ParsedActivity, String?> {
        if (a.elevationGain != null || "altitude" in a.streams) return a to null
        dao.terrainRow(hash)?.let { t ->
            val altitude = json.parseToJsonElement(t.altitude).jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
            if (altitude.size == a.streams[StreamKeys.TIME]?.size) return a.withElevation(altitude, t.elevationGain) to null     // else a different file
        }
        dao.override(hash)?.let { return a.withElevation(null, it.elevationGain) to null }
        val service = terrain
        if (!allowNetwork || service == null || !TerrainElevation.hasTrack(a.streams)) return a to null
        return try {
            val p = service.lookup(a.streams.getValue("lat"), a.streams.getValue("lon"))
            dao.putTerrain(listOf(TerrainElevationEntity(hash, service.provider, p.gain, encodeAltitude(p.altitude))))
            a.withElevation(p.altitude, p.gain) to null
        } catch (e: TerrainException) {
            a to "terrain elevation not added: ${e.message}"
        }
    }

    private fun encodeAltitude(a: DoubleArray) = a.joinToString(",", "[", "]")

    /** Writes the streams file, and the archived original when one is given. Returns the archive name. */
    private fun writeFiles(hash: String, filename: String?, original: ByteArray?, a: ParsedActivity): String? {
        val archiveName = if (filename != null && original != null) Importer.archiveName(hash, filename) else null
        if (archiveName != null) File(importsDir, archiveName).writeBytes(original!!)
        val target = File(streamsDir, "$hash.json.gz")
        File(streamsDir, "$hash.json.gz.tmp").also { it.writeBytes(StreamCodec.encode(a.streams)); it.renameTo(target) }
        return archiveName
    }

    private suspend fun store(hash: String, filename: String, original: ByteArray, a: ParsedActivity, sport: String) {
        val archiveName = writeFiles(hash, filename, original, a)!!
        val now = Times.format(Instant.now())
        db.withTransaction {
            dao.insert(ActivityEntity(hash, sport, Times.format(a.startTime), a.duration, a.distance, a.elevationGain,
                a.device, "imports/$archiveName", now))
            writeChildren(hash, a)
            dao.log(ChangeEntity(entity = "activity", key = hash, op = "put", changedAt = now))
        }
    }

    private suspend fun writeChildren(hash: String, a: ParsedActivity) {
        dao.insertLaps(a.laps.map {
            LapEntity(hash, it.index, it.startTime?.let(Times::format), it.duration, it.distance, it.avgHeartRate,
                it.maxHeartRate, it.avgPower, it.avgSpeed, it.elevationGain)
        })
        dao.putRoute(RouteEntity(hash, Routes.VERSION, json.encodeToString(ListSerializer(String.serializer()), Routes.segments(a.streams))))
    }

    /**
     * Re-parse every archived file and overwrite its activity, keeping its identity and import date: how a parser fix
     * reaches existing data (PC: reparse). An activity the user deleted stays deleted. Derived data is rebuilt too.
     */
    suspend fun rebuild(untyped: UntypedSport, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): RebuildResult =
        withContext(Dispatchers.IO) {
            val files = archiveFiles()
            var rebuilt = 0
            var skipped = 0
            val failed = mutableListOf<String>()
            for ((i, f) in files.withIndex()) {
                try {
                    val decoded = Importer.decode(f.name, f.readBytes())
                    val existing = dao.get(decoded.fileHash)
                    if (existing == null) { skipped++ } else {
                        val parsed = withElevation(decoded.fileHash, Importer.parse(decoded, f.name), allowNetwork = false).first
                        val sport = if (untyped == UntypedSport.GUESS_FOOT && parsed.sport == "unknown")
                            Common.inferFootSport(parsed.distance, parsed.duration) else parsed.sport
                        writeFiles(decoded.fileHash, null, null, parsed)
                        db.withTransaction {
                            dao.insertOrReplace(existing.copy(sport = sport, startTime = Times.format(parsed.startTime), duration = parsed.duration,
                                distance = parsed.distance, elevationGain = parsed.elevationGain, device = parsed.device))
                            dao.deleteLaps(decoded.fileHash)
                            writeChildren(decoded.fileHash, parsed)
                        }
                        rebuilt++
                    }
                } catch (e: ImportFailed) {
                    failed += "${f.name}: ${e.message}"
                }
                if (i % 25 == 0) onProgress(i + 1, files.size)
            }
            onProgress(files.size, files.size)
            RebuildResult(rebuilt, skipped, failed)
        }

    /** Build routes that are missing or made by older logic. Cheap when nothing is outdated, so safe to call at start-up. */
    suspend fun ensureDerived(): Int = withContext(Dispatchers.IO) {
        var built = 0
        for (hash in dao.routesToBuild(Routes.VERSION)) {
            val streams = File(streamsDir, "$hash.json.gz").takeIf { it.exists() }?.let { StreamCodec.decode(it.readBytes()) } ?: continue
            dao.putRoute(RouteEntity(hash, Routes.VERSION, json.encodeToString(ListSerializer(String.serializer()), Routes.segments(streams))))
            built++
        }
        built
    }

    /** Delete activities in one transaction. The raw files stay in `imports/`; each delete leaves a tombstone. */
    suspend fun delete(hashes: Collection<String>) = withContext(Dispatchers.IO) {
        val now = Times.format(Instant.now())
        for (chunk in hashes.chunked(500)) db.withTransaction {
            for (h in dao.existing(chunk)) dao.log(ChangeEntity(entity = "activity", key = h, op = "delete", changedAt = now))
            dao.delete(chunk)
        }
        hashes.forEach { File(streamsDir, "$it.json.gz").delete() }
        afterDelete?.invoke()
    }

    suspend fun sportOf(hash: String): String? = dao.get(hash)?.sport

    /** The feed, newest first. [fromTime] inclusive, [toTime] exclusive; null leaves that side open. */
    fun list(fromTime: Instant?, toTime: Instant?, sport: String?): Flow<List<ActivityListItem>> =
        dao.observeList(fromTime?.let(Times::format), toTime?.let(Times::format), sport).map { rows -> rows.map(::toItem) }

    fun sports(): Flow<List<SportCount>> = dao.observeSports()
    fun count(): Flow<Int> = dao.observeCount()

    suspend fun detail(hash: String): ActivityDetail? = withContext(Dispatchers.IO) {
        val a = dao.get(hash) ?: return@withContext null
        val item = toItem(ActivityListRow(a.fileHash, a.sport, a.startTime, a.duration, a.distance, a.elevationGain, a.device, dao.route(hash)))
        ActivityDetail(item, dao.laps(hash).map {
            LapItem(it.lapIndex, it.duration, it.distance, it.avgHeartRate, it.maxHeartRate, it.avgPower, it.avgSpeed, it.elevationGain)
        }, a.importedAt)
    }

    /** The activity's per-second streams, or null when its file is missing. */
    suspend fun streams(hash: String): Map<String, DoubleArray>? = withContext(Dispatchers.IO) {
        File(streamsDir, "$hash.json.gz").takeIf { it.exists() }?.let { StreamCodec.decode(it.readBytes()) }
    }

    /**
     * Fill climb from Strava's `activities.csv` ([gains] from [StravaCsv.elevationGains]) for activities that have none, matching
     * the CSV's file names to the archived originals. Kept in `elevation_overrides` so a rebuild puts it back. Returns how many were filled.
     */
    suspend fun applyStravaCsv(gains: Map<String, Double>): Int = withContext(Dispatchers.IO) {
        val rows = ArrayList<ElevationOverrideEntity>()
        for (g in dao.withoutElevation()) {
            val gain = StravaCsv.originalName(g.sourceFile)?.let(gains::get) ?: continue
            rows += ElevationOverrideEntity(g.fileHash, gain, StravaCsv.SOURCE)
        }
        db.withTransaction {
            dao.putOverrides(rows)
            rows.forEach { dao.setElevation(it.fileHash, it.elevationGain) }
        }
        rows.size
    }

    /** Where an activity's climb came from outside its file: "Strava", "terrain data", or null. */
    suspend fun elevationSource(hash: String): String? = when {
        dao.override(hash) != null -> "Strava"
        dao.terrainRow(hash) != null -> "terrain data"
        else -> null
    }

    fun missingElevationCount(): Flow<Int> = dao.observeGpsWithoutElevation()

    /** What a terrain look-up over existing activities did. */
    class TerrainFill(val filled: Int, val failed: Int, val firstError: String?)

    /** Look up terrain climb for every activity with a track and none: a deliberate, one-off action over the existing history. */
    suspend fun fillTerrain(service: TerrainService, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): TerrainFill = withContext(Dispatchers.IO) {
        val todo = dao.gpsWithoutElevation()
        var filled = 0
        var failed = 0
        var firstError: String? = null
        for ((i, g) in todo.withIndex()) {
            val streams = File(streamsDir, "${g.fileHash}.json.gz").takeIf { it.exists() }?.let { StreamCodec.decode(it.readBytes()) }
            if (streams == null || !TerrainElevation.hasTrack(streams)) { failed++; continue }
            try {
                val p = service.lookup(streams.getValue("lat"), streams.getValue("lon"))
                val target = File(streamsDir, "${g.fileHash}.json.gz")
                File(streamsDir, "${g.fileHash}.json.gz.tmp").also { it.writeBytes(StreamCodec.encode(streams + ("altitude" to p.altitude))); it.renameTo(target) }
                db.withTransaction {
                    dao.putTerrain(listOf(TerrainElevationEntity(g.fileHash, service.provider, p.gain, encodeAltitude(p.altitude))))
                    dao.setElevation(g.fileHash, p.gain)
                }
                filled++
            } catch (e: TerrainException) {
                failed++
                if (firstError == null) firstError = e.message
                if (e.message?.startsWith("gave up") == true) break      // the service stopped trying: so do we
            }
            if (i % 5 == 0) onProgress(i + 1, todo.size)
        }
        onProgress(todo.size, todo.size)
        TerrainFill(filled, failed, firstError)
    }

    /** The climb kept apart from the files, as JSON for a backup. */
    suspend fun exportElevation(): String = withContext(Dispatchers.IO) {
        val o = dao.allOverrides().joinToString(",") { """{"hash":"${it.fileHash}","gain":${it.elevationGain},"source":"${it.source}"}""" }
        val t = dao.allTerrain().joinToString(",") { """{"hash":"${it.fileHash}","provider":"${it.provider}","gain":${it.elevationGain},"altitude":${it.altitude}}""" }
        """{"overrides":[$o],"terrain":[$t]}"""
    }

    /** Read what [exportElevation] wrote, before the files are imported so each one picks its climb up. */
    suspend fun importElevation(text: String) = withContext(Dispatchers.IO) {
        val root = json.parseToJsonElement(text).jsonObject
        val overrides = root["overrides"]?.jsonArray?.map {
            val o = it.jsonObject
            ElevationOverrideEntity(o.getValue("hash").jsonPrimitive.content, o.getValue("gain").jsonPrimitive.content.toDouble(), o.getValue("source").jsonPrimitive.content)
        } ?: emptyList()
        val terrain = root["terrain"]?.jsonArray?.map {
            val o = it.jsonObject
            TerrainElevationEntity(o.getValue("hash").jsonPrimitive.content, o.getValue("provider").jsonPrimitive.content, o.getValue("gain").jsonPrimitive.content.toDouble(), o.getValue("altitude").toString())
        } ?: emptyList()
        db.withTransaction { dao.putOverrides(overrides); dao.putTerrain(terrain) }
    }

    /** Forget which folder files were seen, so the next folder import looks at every file again. */
    suspend fun clearImportLog() = dao.clearLog()

    suspend fun storageUsage(databaseFile: File?): StorageUsage = withContext(Dispatchers.IO) {
        fun size(dir: File) = dir.listFiles()?.sumOf { it.length() } ?: 0L
        StorageUsage(size(importsDir), size(streamsDir), databaseFile?.let { f -> listOf("", "-wal", "-shm").sumOf { File(f.path + it).length() } } ?: 0L)
    }

    private fun toItem(r: ActivityListRow) = ActivityListItem(
        r.fileHash, r.sport, Times.parse(r.startTime)!!, r.duration, r.distance, r.elevationGain, r.device,
        r.polyline?.let { json.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList(),
    )
}
