package app.fitness.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Phase 2 tables. `settings` is user data (and syncs); the rest is derived: versioned, rebuilt when missing or outdated,
 * never edited in place. All keyed by `file_hash` like the rest of the schema.
 */

/** Per-activity stream summaries plus the load score computed from them with the thresholds in force. */
@Entity(
    tableName = "activity_metrics",
    foreignKeys = [ForeignKey(ActivityEntity::class, ["file_hash"], ["file_hash"], onDelete = ForeignKey.CASCADE)],
)
data class MetricsEntity(
    @PrimaryKey @ColumnInfo(name = "file_hash") val fileHash: String,
    val version: Int,
    @ColumnInfo(name = "normalized_power") val normalizedPower: Double?,
    @ColumnInfo(name = "power_seconds") val powerSeconds: Double?,
    @ColumnInfo(name = "best_20min_power") val best20minPower: Double?,
    @ColumnInfo(name = "hr_max") val hrMax: Double?,
    /** JSON object: whole bpm to seconds spent there. */
    @ColumnInfo(name = "hr_hist") val hrHist: String?,
    val load: Double?,
    @ColumnInfo(name = "load_method") val loadMethod: String?,
    /** JSON object: tenths of m/s to seconds spent moving at that speed. */
    @ColumnInfo(name = "speed_hist") val speedHist: String? = null,
    /** JSON array of candidate interval reps (running only). */
    val reps: String? = null,
)

/** Best efforts of one activity as JSON: `{"power": {"5": w}, "speed": {"60": m/s}, "distance_time": {"5000.0": s}}`. */
@Entity(
    tableName = "activity_bests",
    foreignKeys = [ForeignKey(ActivityEntity::class, ["file_hash"], ["file_hash"], onDelete = ForeignKey.CASCADE)],
)
data class BestsEntity(
    @PrimaryKey @ColumnInfo(name = "file_hash") val fileHash: String,
    val version: Int,
    val data: String,
)

/** One local calendar day of load and the fitness (CTL), fatigue (ATL) and form (TSB) at the start of it. */
@Entity(tableName = "daily_load")
data class DailyLoadEntity(
    /** Local date, `yyyy-MM-dd`. */
    @PrimaryKey val date: String,
    val load: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
)

/** A threshold the user set (ftp, max_hr, resting_hr, threshold_hr). A missing row means "use the estimate". */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/** What the load calculation needs about one activity. */
data class LoadInput(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    val sport: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    val duration: Double?,
    @ColumnInfo(name = "normalized_power") val normalizedPower: Double?,
    @ColumnInfo(name = "power_seconds") val powerSeconds: Double?,
    @ColumnInfo(name = "hr_hist") val hrHist: String?,
)

data class BestsRow(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    val data: String,
)

data class ActivityLoadRow(
    val load: Double?,
    @ColumnInfo(name = "load_method") val loadMethod: String?,
    @ColumnInfo(name = "normalized_power") val normalizedPower: Double?,
)

data class PosterRow(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    val distance: Double?,
    val duration: Double?,
    val load: Double?,
    val polyline: String?,
    @ColumnInfo(name = "speed_hist") val speedHist: String?,
    @ColumnInfo(name = "hr_hist") val hrHist: String?,
    val reps: String?,
)

@Dao
interface AnalyticsDao {
    @Query(
        """SELECT a.file_hash, a.start_time, a.distance, a.duration, m.load, r.polyline, m.speed_hist, m.hr_hist, m.reps FROM activities a
           LEFT JOIN activity_metrics m ON m.file_hash = a.file_hash LEFT JOIN routes r ON r.file_hash = a.file_hash
           WHERE a.sport = 'running' ORDER BY a.start_time"""
    )
    suspend fun posterRows(): List<PosterRow>

    /** Activities with no current metrics or bests. */
    @Query(
        """SELECT a.file_hash FROM activities a
           LEFT JOIN activity_metrics m ON m.file_hash = a.file_hash
           LEFT JOIN activity_bests b ON b.file_hash = a.file_hash
           WHERE m.file_hash IS NULL OR m.version < :metricsVersion OR b.file_hash IS NULL OR b.version < :bestsVersion"""
    )
    suspend fun toBuild(metricsVersion: Int, bestsVersion: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetrics(rows: List<MetricsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBests(rows: List<BestsEntity>)

    @Query(
        """SELECT a.file_hash, a.sport, a.start_time, a.duration, m.normalized_power, m.power_seconds, m.hr_hist
           FROM activities a JOIN activity_metrics m ON m.file_hash = a.file_hash"""
    )
    suspend fun loadInputs(): List<LoadInput>

    @Query("UPDATE activity_metrics SET load = :load, load_method = :method WHERE file_hash = :hash")
    suspend fun setLoad(hash: String, load: Double, method: String)

    @Query("SELECT MAX(hr_max) FROM activity_metrics")
    fun observeMaxHr(): Flow<Double?>

    @Query(
        """SELECT MAX(m.best_20min_power) FROM activity_metrics m JOIN activities a ON a.file_hash = m.file_hash
           WHERE a.sport IN ('cycling', 'e_biking')"""
    )
    fun observeBest20(): Flow<Double?>

    @Query("SELECT * FROM settings")
    fun observeSettings(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun settings(): List<SettingEntity>

    @Query("SELECT MAX(hr_max) FROM activity_metrics")
    suspend fun maxHr(): Double?

    @Query(
        """SELECT MAX(m.best_20min_power) FROM activity_metrics m JOIN activities a ON a.file_hash = m.file_hash
           WHERE a.sport IN ('cycling', 'e_biking')"""
    )
    suspend fun best20(): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(s: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    @Query("DELETE FROM daily_load")
    suspend fun clearDaily()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDaily(rows: List<DailyLoadEntity>)

    @Query("SELECT * FROM daily_load ORDER BY date")
    fun observeDaily(): Flow<List<DailyLoadEntity>>

    @Query("SELECT MAX(date) FROM daily_load")
    suspend fun lastDay(): String?

    @Query("SELECT load, load_method, normalized_power FROM activity_metrics WHERE file_hash = :hash")
    suspend fun loadOf(hash: String): ActivityLoadRow?

    @Query(
        """SELECT a.file_hash, a.start_time, b.data FROM activities a JOIN activity_bests b ON b.file_hash = a.file_hash
           WHERE a.sport = :sport AND b.data != :empty ORDER BY a.start_time"""
    )
    suspend fun bestsFor(sport: String, empty: String): List<BestsRow>

    @Query(
        """SELECT a.sport AS sport, COUNT(*) AS n FROM activities a JOIN activity_bests b ON b.file_hash = a.file_hash
           WHERE b.data != :empty GROUP BY a.sport ORDER BY n DESC"""
    )
    suspend fun bestSports(empty: String): List<SportCount>
}
