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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/**
 * One row per file a folder import has seen, so a run that is stopped or killed carries on where it left off and a later
 * run over the same folder only looks at new files. Keyed by the tree and document ids, with the size and modified time
 * that were seen (a changed file is looked at again). Not synced: it only describes this phone's imports.
 */
@Entity(tableName = "import_log")
data class ImportLogEntity(
    @PrimaryKey @ColumnInfo(name = "doc_key") val docKey: String,
    val size: Long,
    val modified: Long,
    /** `imported`, `duplicate`, `similar` or `failed`. */
    val outcome: String,
    @ColumnInfo(name = "file_hash") val fileHash: String?,
    val message: String?,
    @ColumnInfo(name = "logged_at") val loggedAt: String,
)

data class ImportLogKey(
    @ColumnInfo(name = "doc_key") val docKey: String,
    val size: Long,
    val modified: Long,
    val outcome: String,
)

data class CandidateRow(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    val distance: Double,
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

    /** Stored activities worth comparing with a new file for same-run detection (dates are UTC `yyyy-MM-dd`, inclusive). */
    @Query(
        """SELECT file_hash, start_time, distance FROM activities
           WHERE (:anySport OR sport = :sport) AND distance > 0 AND substr(start_time, 1, 10) BETWEEN :fromDate AND :toDate"""
    )
    suspend fun candidates(sport: String, anySport: Boolean, fromDate: String, toDate: String): List<CandidateRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(activity: ActivityEntity)

    @Query("DELETE FROM laps WHERE file_hash = :hash")
    suspend fun deleteLaps(hash: String)

    @Query("SELECT imported_at FROM activities WHERE file_hash = :hash")
    suspend fun importedAt(hash: String): String?

    /** Activities with no route, or one built by older logic (derived data is rebuilt when missing or outdated). */
    @Query(
        """SELECT a.file_hash FROM activities a LEFT JOIN routes r ON r.file_hash = a.file_hash
           WHERE r.file_hash IS NULL OR r.version < :version"""
    )
    suspend fun routesToBuild(version: Int): List<String>

    @Query("SELECT doc_key, size, modified, outcome FROM import_log WHERE substr(doc_key, 1, :prefixLength) = :prefix")
    suspend fun logFor(prefix: String, prefixLength: Int): List<ImportLogKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLog(entries: List<ImportLogEntity>)

    @Query("DELETE FROM import_log")
    suspend fun clearLog()

    @Query("SELECT COUNT(*) FROM import_log")
    suspend fun logSize(): Int
}

@Database(
    entities = [
        ActivityEntity::class, LapEntity::class, RouteEntity::class, ChangeEntity::class, ImportLogEntity::class,
        MetricsEntity::class, BestsEntity::class, DailyLoadEntity::class, SettingEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun analytics(): AnalyticsDao

    companion object {
        /** Version 2 added the import log. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_log` (`doc_key` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, `file_hash` TEXT, `message` TEXT, `logged_at` TEXT NOT NULL, PRIMARY KEY(`doc_key`))"
                )
            }
        }

        /** Version 3 added the analytics tables (metrics, bests, daily load) and the thresholds. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_metrics` (`file_hash` TEXT NOT NULL, `version` INTEGER NOT NULL, `normalized_power` REAL, " +
                        "`power_seconds` REAL, `best_20min_power` REAL, `hr_max` REAL, `hr_hist` TEXT, `load` REAL, `load_method` TEXT, PRIMARY KEY(`file_hash`), " +
                        "FOREIGN KEY(`file_hash`) REFERENCES `activities`(`file_hash`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_bests` (`file_hash` TEXT NOT NULL, `version` INTEGER NOT NULL, `data` TEXT NOT NULL, PRIMARY KEY(`file_hash`), " +
                        "FOREIGN KEY(`file_hash`) REFERENCES `activities`(`file_hash`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_load` (`date` TEXT NOT NULL, `load` REAL NOT NULL, `ctl` REAL NOT NULL, `atl` REAL NOT NULL, " +
                        "`tsb` REAL NOT NULL, PRIMARY KEY(`date`))"
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` REAL NOT NULL, `updated_at` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        /** Version 4 added the running profile (speed histogram, interval reps) to the metrics. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activity_metrics` ADD COLUMN `speed_hist` TEXT")
                db.execSQL("ALTER TABLE `activity_metrics` ADD COLUMN `reps` TEXT")
                // The stored metrics lack the profile: drop them so the next start rebuilds them from the stream files.
                db.execSQL("DELETE FROM `activity_metrics`")
                db.execSQL("DELETE FROM `activity_bests`")
            }
        }

        fun create(context: Context): FitnessDatabase =
            Room.databaseBuilder(context.applicationContext, FitnessDatabase::class.java, "fitness.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
