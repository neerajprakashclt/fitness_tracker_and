package app.fitness.data

import androidx.room.withTransaction
import app.fitness.core.Common
import app.fitness.core.ImportFailed
import app.fitness.core.Importer
import app.fitness.core.ParsedActivity
import app.fitness.core.Routes
import app.fitness.core.StreamCodec
import app.fitness.core.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** What importing one file did. */
sealed interface ImportOutcome {
    data class Imported(val fileHash: String, val sport: String) : ImportOutcome
    /** The same file (by content) is already in the database. */
    data class Duplicate(val fileHash: String) : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

/** The sport stored for a file whose format records none: leave it "unknown", or guess running/walking from pace. */
enum class UntypedSport { UNKNOWN, GUESS_FOOT }

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

    suspend fun import(filename: String, data: ByteArray, untyped: UntypedSport = UntypedSport.UNKNOWN): ImportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val decoded = Importer.decode(filename, data)
                if (dao.exists(decoded.fileHash)) return@withContext ImportOutcome.Duplicate(decoded.fileHash)
                val parsed = Importer.parse(decoded, filename)
                val sport = if (untyped == UntypedSport.GUESS_FOOT && parsed.sport == "unknown")
                    Common.inferFootSport(parsed.distance, parsed.duration) else parsed.sport
                store(decoded.fileHash, filename, data, parsed, sport)
                ImportOutcome.Imported(decoded.fileHash, sport)
            } catch (e: ImportFailed) {
                ImportOutcome.Failed(e.message ?: "Import failed")
            }
        }

    private suspend fun store(hash: String, filename: String, original: ByteArray, a: ParsedActivity, sport: String) {
        val archiveName = Importer.archiveName(hash, filename)
        File(importsDir, archiveName).writeBytes(original)
        val streams = File(streamsDir, "$hash.json.gz")
        File(streamsDir, "$hash.json.gz.tmp").also { it.writeBytes(StreamCodec.encode(a.streams)); it.renameTo(streams) }
        val now = Times.format(Instant.now())
        val route = Routes.segments(a.streams)
        db.withTransaction {
            dao.insert(ActivityEntity(hash, sport, Times.format(a.startTime), a.duration, a.distance, a.elevationGain,
                a.device, "imports/$archiveName", now))
            dao.insertLaps(a.laps.map {
                LapEntity(hash, it.index, it.startTime?.let(Times::format), it.duration, it.distance, it.avgHeartRate,
                    it.maxHeartRate, it.avgPower, it.avgSpeed, it.elevationGain)
            })
            dao.putRoute(RouteEntity(hash, Routes.VERSION, json.encodeToString(ListSerializer(String.serializer()), route)))
            dao.log(ChangeEntity(entity = "activity", key = hash, op = "put", changedAt = now))
        }
    }

    /** Delete activities in one transaction. The raw files stay in `imports/`; each delete leaves a tombstone. */
    suspend fun delete(hashes: Collection<String>) = withContext(Dispatchers.IO) {
        val now = Times.format(Instant.now())
        for (chunk in hashes.chunked(500)) db.withTransaction {
            for (h in dao.existing(chunk)) dao.log(ChangeEntity(entity = "activity", key = h, op = "delete", changedAt = now))
            dao.delete(chunk)
        }
        hashes.forEach { File(streamsDir, "$it.json.gz").delete() }
    }

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

    private fun toItem(r: ActivityListRow) = ActivityListItem(
        r.fileHash, r.sport, Times.parse(r.startTime)!!, r.duration, r.distance, r.elevationGain, r.device,
        r.polyline?.let { json.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList(),
    )
}
