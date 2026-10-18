package app.fitness.data

import androidx.room.withTransaction
import app.fitness.core.Bests
import app.fitness.core.Curves
import app.fitness.core.CurvePoint
import app.fitness.core.DayLoad
import app.fitness.core.EffectiveSettings
import app.fitness.core.Times
import app.fitness.core.TrainingLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

/** What the activity detail shows about training load. */
class LoadInfo(val load: Double, val method: String, val normalizedPower: Double?)

/**
 * Derived analytics: per-activity metrics and best efforts, the load score, and CTL/ATL/TSB per day, all rebuilt from the
 * per-second streams (PC: `training_load.recompute`, `power_curve.recompute`). The thresholds are the one piece of user
 * data here; everything else can be thrown away and recomputed.
 */
class AnalyticsRepository(private val db: FitnessDatabase, private val repo: ActivityRepository) {
    private val dao = db.analytics()
    private val mutex = Mutex()
    private val json = Json

    companion object {
        /** Bump when [TrainingLoad.streamMetrics] changes. The PC's own version is 3, but this table has different columns. */
        const val METRICS_VERSION = 1
        /** Bump when [Curves.bestEfforts] changes. */
        const val BESTS_VERSION = 1
        private val EMPTY_BESTS = encode(Bests())

        internal fun encode(b: Bests): String = buildJsonObject {
            put("power", buildJsonObject { b.power.forEach { (k, v) -> put(k.toString(), JsonPrimitive(v)) } })
            put("speed", buildJsonObject { b.speed.forEach { (k, v) -> put(k.toString(), JsonPrimitive(v)) } })
            put("distance_time", buildJsonObject { b.distanceTime.forEach { (k, v) -> put(k.toString(), JsonPrimitive(v)) } })
        }.toString()

        internal fun decode(text: String): Bests {
            val o = Json.parseToJsonElement(text).jsonObject
            fun <K> side(name: String, key: (String) -> K): Map<K, Double> =
                (o[name] as? JsonObject)?.entries?.associate { key(it.key) to it.value.jsonPrimitive.content.toDouble() } ?: emptyMap()
            return Bests(side("power") { it.toInt() }, side("speed") { it.toInt() }, side("distance_time") { it.toDouble() })
        }
    }

    /** How many activities still need their metrics (zero when everything is current). */
    suspend fun outdatedCount(): Int = dao.toBuild(METRICS_VERSION, BESTS_VERSION).size

    /** Compute what is missing, then the load and daily series. Safe to call at any time; callers wait for each other. */
    suspend fun refresh(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }) = mutex.withLock {
        withContext(Dispatchers.Default) {
            ensureMetrics(onProgress)
            recompute()
        }
    }

    /** Recompute load and the daily series from the stored metrics (after a threshold change, a delete, or a new day). */
    suspend fun recomputeLoad() = mutex.withLock { withContext(Dispatchers.Default) { recompute() } }

    private suspend fun ensureMetrics(onProgress: suspend (Int, Int) -> Unit) {
        val todo = dao.toBuild(METRICS_VERSION, BESTS_VERSION)
        val batchM = ArrayList<MetricsEntity>()
        val batchB = ArrayList<BestsEntity>()
        suspend fun flush() {
            if (batchM.isEmpty()) return
            db.withTransaction { dao.putMetrics(batchM.toList()); dao.putBests(batchB.toList()) }
            batchM.clear(); batchB.clear()
        }
        for ((i, hash) in todo.withIndex()) {
            val streams = repo.streams(hash)
            val sport = repo.sportOf(hash)
            if (streams != null && sport != null) {
                val m = TrainingLoad.streamMetrics(streams)
                batchM += MetricsEntity(hash, METRICS_VERSION, m.normalizedPower, m.powerSeconds, m.best20minPower, m.hrMax,
                    m.hrHist?.let { h -> buildJsonObject { h.forEach { (k, v) -> put(k.toString(), JsonPrimitive(v)) } }.toString() }, null, null)
                batchB += BestsEntity(hash, BESTS_VERSION, encode(Curves.bestEfforts(sport, streams)))
            } else {
                // No stream file: record empty metrics so it is not retried on every start.
                batchM += MetricsEntity(hash, METRICS_VERSION, null, null, null, null, null, null, null)
                batchB += BestsEntity(hash, BESTS_VERSION, EMPTY_BESTS)
            }
            if (batchM.size >= 50) flush()
            if (i % 25 == 0) onProgress(i + 1, todo.size)
        }
        flush()
        onProgress(todo.size, todo.size)
    }

    private suspend fun recompute() {
        val stored = dao.settings().associate { it.key to it.value }
        val settings = TrainingLoad.effectiveSettings(stored, dao.maxHr(), dao.best20())
        val t = settings.values
        val perDay = HashMap<LocalDate, Double>()
        val updates = ArrayList<Triple<String, Double, String>>()
        for (r in dao.loadInputs()) {
            val hist = r.hrHist?.let { h -> json.parseToJsonElement(h).jsonObject.entries.associate { it.key.toInt() to it.value.jsonPrimitive.content.toDouble() } }
            val m = app.fitness.core.StreamMetrics(r.normalizedPower, r.powerSeconds, null, null, hist)
            val load = TrainingLoad.activityLoad(r.sport, r.duration, m, t)
            updates += Triple(r.fileHash, load.load, load.method.label)
            val start = Times.parse(r.startTime) ?: continue
            perDay.merge(TrainingLoad.localDate(start), load.load, Double::plus)
        }
        val series = TrainingLoad.dailySeries(perDay, LocalDate.now())
        db.withTransaction {
            for ((h, l, method) in updates) dao.setLoad(h, l, method)
            dao.clearDaily()
            series.chunked(1000).forEach { chunk -> dao.putDaily(chunk.map { DailyLoadEntity(it.date.toString(), it.load, it.ctl, it.atl, it.tsb) }) }
        }
    }

    /** The thresholds in force: what the user set, else an estimate from the data. */
    fun settings(): Flow<EffectiveSettings> =
        combine(dao.observeSettings(), dao.observeMaxHr(), dao.observeBest20()) { stored, maxHr, best20 ->
            TrainingLoad.effectiveSettings(stored.associate { it.key to it.value }, maxHr, best20)
        }

    /** Set a threshold, or clear it with null to go back to the estimate. Recomputes the load. */
    suspend fun saveSetting(key: String, value: Double?) {
        require(key in TrainingLoad.SETTING_KEYS) { "Unknown setting: $key" }
        val now = Times.format(Instant.now())
        db.withTransaction {
            if (value == null) dao.deleteSetting(key) else dao.putSetting(SettingEntity(key, value, now))
            repo.dao().log(ChangeEntity(entity = "setting", key = key, op = if (value == null) "delete" else "put", changedAt = now))
        }
        recomputeLoad()
    }

    fun daily(): Flow<List<DayLoad>> = dao.observeDaily().map { rows ->
        rows.map { DayLoad(LocalDate.parse(it.date), it.load, it.ctl, it.atl, it.tsb) }
    }

    suspend fun loadOf(hash: String): LoadInfo? = dao.loadOf(hash)?.takeIf { it.load != null && it.loadMethod != null }
        ?.let { LoadInfo(it.load!!, it.loadMethod!!, it.normalizedPower) }

    /** Sports that have any best-effort data, most frequent first. */
    suspend fun curveSports(): List<String> = dao.bestSports(EMPTY_BESTS).map { it.sport }

    /** The best effort per span for one sport, optionally within a local date range. */
    suspend fun curves(sport: String, from: LocalDate? = null, to: LocalDate? = null): Map<Curves.Metric, List<CurvePoint>> =
        withContext(Dispatchers.Default) {
            val entries = dao.bestsFor(sport, EMPTY_BESTS).mapNotNull { r ->
                val start = Times.parse(r.startTime) ?: return@mapNotNull null
                Curves.Entry(r.fileHash, TrainingLoad.localDate(start), decode(r.data))
            }
            Curves.aggregate(entries, from, to)
        }
}
