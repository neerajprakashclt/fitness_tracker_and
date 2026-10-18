package app.fitness.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/*
 * The schema mirrors the PC app's, with `file_hash` (sha256 of the decompressed file) as the identity instead of an
 * auto-increment id, so a later sync maps rows one to one. Streams are not here: they live in
 * `filesDir/streams/<file_hash>.json.gz` (a plain blob fails to read back from about 2.1 MB).
 */

@Entity(
    tableName = "activities",
    indices = [Index("start_time"), Index("sport")],
)
data class ActivityEntity(
    @PrimaryKey @ColumnInfo(name = "file_hash") val fileHash: String,
    val sport: String,
    /** UTC, ISO 8601 as the PC writes it (`2026-08-27T12:08:03+00:00`), so it sorts as text. */
    @ColumnInfo(name = "start_time") val startTime: String,
    /** Moving time in seconds. */
    val duration: Double?,
    /** Metres. */
    val distance: Double?,
    @ColumnInfo(name = "elevation_gain") val elevationGain: Double?,
    val device: String?,
    /** The archived original, relative to `filesDir`. */
    @ColumnInfo(name = "source_file") val sourceFile: String,
    @ColumnInfo(name = "imported_at") val importedAt: String,
)

@Entity(
    tableName = "laps",
    primaryKeys = ["file_hash", "lap_index"],
    foreignKeys = [ForeignKey(ActivityEntity::class, ["file_hash"], ["file_hash"], onDelete = ForeignKey.CASCADE)],
)
data class LapEntity(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "lap_index") val lapIndex: Int,
    @ColumnInfo(name = "start_time") val startTime: String?,
    val duration: Double?,
    val distance: Double?,
    @ColumnInfo(name = "avg_heart_rate") val avgHeartRate: Double?,
    @ColumnInfo(name = "max_heart_rate") val maxHeartRate: Double?,
    @ColumnInfo(name = "avg_power") val avgPower: Double?,
    @ColumnInfo(name = "avg_speed") val avgSpeed: Double?,
    @ColumnInfo(name = "elevation_gain") val elevationGain: Double?,
)

/** Derived (versioned, rebuilt when missing or outdated): the simplified track, as a JSON list of encoded polylines. */
@Entity(
    tableName = "routes",
    foreignKeys = [ForeignKey(ActivityEntity::class, ["file_hash"], ["file_hash"], onDelete = ForeignKey.CASCADE)],
)
data class RouteEntity(
    @PrimaryKey @ColumnInfo(name = "file_hash") val fileHash: String,
    val version: Int,
    val polyline: String,
)

/** Append-only log for a future sync: imports, deletes (tombstones) and settings edits. */
@Entity(tableName = "changes")
data class ChangeEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entity: String,
    val key: String,
    /** `put` or `delete`. */
    val op: String,
    @ColumnInfo(name = "changed_at") val changedAt: String,
)

/** One row of the activity feed: the activity and its route in a single query. */
data class ActivityListRow(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    val sport: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    val duration: Double?,
    val distance: Double?,
    @ColumnInfo(name = "elevation_gain") val elevationGain: Double?,
    val device: String?,
    val polyline: String?,
)

data class SportCount(val sport: String, val n: Int)

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(activity: ActivityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLaps(laps: List<LapEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoute(route: RouteEntity)

    @Insert
    suspend fun log(change: ChangeEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM activities WHERE file_hash = :hash)")
    suspend fun exists(hash: String): Boolean

    @Query("SELECT * FROM activities WHERE file_hash = :hash")
    suspend fun get(hash: String): ActivityEntity?

    @Query("SELECT * FROM laps WHERE file_hash = :hash ORDER BY lap_index")
    suspend fun laps(hash: String): List<LapEntity>

    @Query("SELECT polyline FROM routes WHERE file_hash = :hash")
    suspend fun route(hash: String): String?

    /** [fromTime] inclusive and [toTime] exclusive, both UTC text in the stored format; null means unbounded. */
    @Query(
        """SELECT a.file_hash, a.sport, a.start_time, a.duration, a.distance, a.elevation_gain, a.device, r.polyline
           FROM activities a LEFT JOIN routes r ON r.file_hash = a.file_hash
           WHERE (:fromTime IS NULL OR a.start_time >= :fromTime) AND (:toTime IS NULL OR a.start_time < :toTime)
             AND (:sport IS NULL OR a.sport = :sport)
           ORDER BY a.start_time DESC"""
    )
    fun observeList(fromTime: String?, toTime: String?, sport: String?): Flow<List<ActivityListRow>>

    @Query("SELECT sport, COUNT(*) AS n FROM activities GROUP BY sport ORDER BY n DESC")
    fun observeSports(): Flow<List<SportCount>>

    @Query("SELECT COUNT(*) FROM activities")
    fun observeCount(): Flow<Int>

    @Query("SELECT file_hash FROM activities WHERE file_hash IN (:hashes)")
    suspend fun existing(hashes: List<String>): List<String>

    @Query("DELETE FROM activities WHERE file_hash IN (:hashes)")
    suspend fun delete(hashes: List<String>)

    @Query("SELECT file_hash FROM activities")
    suspend fun allHashes(): List<String>

    @Query("SELECT * FROM changes ORDER BY seq")
    suspend fun changes(): List<ChangeEntity>
}

@Database(
    entities = [ActivityEntity::class, LapEntity::class, RouteEntity::class, ChangeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao

    companion object {
        fun create(context: Context): FitnessDatabase =
            Room.databaseBuilder(context.applicationContext, FitnessDatabase::class.java, "fitness.db").build()
    }
}
