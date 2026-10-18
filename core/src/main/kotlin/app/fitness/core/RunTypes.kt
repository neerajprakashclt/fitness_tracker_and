package app.fitness.core

import java.time.LocalDate
import kotlin.math.sqrt

enum class RunType(val label: String) {
    EASY("easy"), STEADY("steady"), TEMPO("tempo"), LONG("long"), INTERVALS("intervals"), UNKNOWN("unknown");
}

/** What a run's type was judged on: [basis] is "profile", "distance", "pace", "hr" or null. */
class RunClass(val type: RunType, val basis: String?, val easyShare: Double? = null, val hardShare: Double? = null)

/** What the classifier needs to know about one run. */
class RunInput(
    val key: String,
    val date: LocalDate,
    val distance: Double?,
    val duration: Double?,
    val speedHist: Map<Int, Double>?,
    val hrHist: Map<Int, Double>?,
    val reps: List<Rep>?,
)

/**
 * Run types (PC: `run_types.py`): intervals, long, tempo, easy or steady, judged against the athlete's own fitness on the
 * day (critical speed as of that date, and the typical run distance of the surrounding months). Order of precedence:
 * intervals, long, tempo, easy, steady. A run with no intensity data is unknown.
 */
object RunTypes {
    const val MIN_INTERVAL_REPS = 3
    const val MAX_INTERVAL_REPS = 30
    const val MAX_REP_SPEED_CV = 0.15
    const val MAX_REP_DURATION_CV = 0.40
    const val HARD_REP_FRACTION_OF_CS = 0.95
    const val MIN_JOG_RECOVERY_SPEED = 1.9
    const val LONG_RATIO = 1.5
    const val LONG_MIN_DISTANCE = 7000.0
    const val LONG_ABSOLUTE_SECONDS = 90 * 60
    const val LONG_ABSOLUTE_DISTANCE = 16000.0
    const val CONTEXT_DAYS = 90
    const val MIN_CONTEXT_RUNS = 5
    const val TEMPO_HARD_SHARE = 0.40
    const val TEMPO_MIN_SECONDS = 20 * 60
    const val EASY_SHARE = 0.75
    /** 0-based index of the first "hard" zone in both zone sets. */
    const val HARD_ZONE_FROM = 2

    /** Population standard deviation over the mean. */
    private fun cv(values: List<Double>): Double {
        val mean = values.average()
        if (mean == 0.0) return Double.POSITIVE_INFINITY
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size) / mean
    }

    fun intervalReps(reps: List<Rep>?, cs: Double?): List<Rep> {
        val all = reps ?: return emptyList()
        val hard = if (cs != null && cs != 0.0) all.filter { it.speed >= HARD_REP_FRACTION_OF_CS * cs }
        else all.filter { it.recoverySpeed == null || it.recoverySpeed >= MIN_JOG_RECOVERY_SPEED }
        if (hard.size !in MIN_INTERVAL_REPS..MAX_INTERVAL_REPS) return emptyList()
        if (cv(hard.map { it.speed }) > MAX_REP_SPEED_CV || cv(hard.map { it.duration.toDouble() }) > MAX_REP_DURATION_CV) return emptyList()
        return hard
    }

    private class Shares(val easy: Double, val hard: Double, val basis: String)

    private fun shares(run: RunInput, cs: Double?, maxHr: Double?): Shares? {
        val seconds: DoubleArray
        val basis: String
        if (cs != null && cs != 0.0 && !run.speedHist.isNullOrEmpty()) { seconds = CriticalSpeed.timeInZones(run.speedHist, cs); basis = "pace" }
        else if (maxHr != null && maxHr != 0.0 && !run.hrHist.isNullOrEmpty()) { seconds = Zones.timeInZones(run.hrHist, maxHr); basis = "hr" }
        else return null
        val total = seconds.sum()
        if (total <= 0) return null
        return Shares(seconds.take(HARD_ZONE_FROM).sum() / total, seconds.drop(HARD_ZONE_FROM).sum() / total, basis)
    }

    fun classify(run: RunInput, cs: Double?, maxHr: Double?, typicalDistance: Double?): RunClass {
        if (intervalReps(run.reps, cs).isNotEmpty()) return RunClass(RunType.INTERVALS, "profile")

        val distance = run.distance ?: 0.0
        val duration = run.duration ?: 0.0
        val relative = typicalDistance != null && typicalDistance != 0.0 && distance >= maxOf(LONG_RATIO * typicalDistance, LONG_MIN_DISTANCE)
        if (relative || duration >= LONG_ABSOLUTE_SECONDS || distance >= LONG_ABSOLUTE_DISTANCE) return RunClass(RunType.LONG, "distance")

        val s = shares(run, cs, maxHr) ?: return RunClass(RunType.UNKNOWN, null)
        val type = when {
            s.hard >= TEMPO_HARD_SHARE && duration >= TEMPO_MIN_SECONDS -> RunType.TEMPO
            s.easy >= EASY_SHARE -> RunType.EASY
            else -> RunType.STEADY
        }
        return RunClass(type, s.basis, s.easy, s.hard)
    }

    /** Median distance of the runs in the [CONTEXT_DAYS] up to and including each run (runs in start order). */
    fun typicalDistances(runs: List<RunInput>): List<Double?> {
        val days = runs.map { it.date.toEpochDay() }
        return runs.indices.map { i ->
            var lo = 0
            while (days[lo] < days[i] - CONTEXT_DAYS) lo++
            val window = (lo..i).mapNotNull { runs[it].distance?.takeIf { d -> d != 0.0 } }.sorted()
            if (window.size < MIN_CONTEXT_RUNS) null
            else if (window.size % 2 == 1) window[window.size / 2] else (window[window.size / 2 - 1] + window[window.size / 2]) / 2
        }
    }

    /** Every run classified, with the critical speed fit of its own day. [runs] in start order. */
    fun classifyAll(runs: List<RunInput>, efforts: List<SpeedEffort>, maxHr: Double?): List<Pair<RunClass, CriticalSpeedFit?>> {
        val timeline = CriticalSpeed.Timeline(efforts)
        return runs.zip(typicalDistances(runs)).map { (run, typical) ->
            val fit = timeline.at(run.date)
            classify(run, fit?.cs, maxHr, typical) to fit
        }
    }
}
