package app.fitness.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class Prediction(val distance: Double, val name: String, val seconds: Double, val speed: Double, val exact: Boolean, val from: CurvePoint)

class Predictions(val predictions: List<Prediction>, val vdot: Double?, val vdotFrom: CurvePoint?)

/** Race-time predictions (Riegel) and a VO2max estimate (Daniels' VDOT) from best recent efforts (PC: `predictions.py`). */
object RacePredictions {
    const val RIEGEL_EXPONENT = 1.06
    val TARGETS = listOf(5000.0 to "5K", 10000.0 to "10K", 21097.5 to "Half marathon", 42195.0 to "Marathon")
    const val MIN_BASIS_DISTANCE = 1000.0
    const val MIN_BASIS_SECONDS = 180.0
    /** A basis effort within 1/2.5x .. 2.5x of the target distance. */
    const val COMPARABLE_RATIO = 2.5
    const val VDOT_MIN_SECONDS = 300.0
    const val VDOT_MAX_SECONDS = 10800.0
    const val WINDOW_DAYS = 180

    fun riegel(seconds: Double, fromM: Double, toM: Double) = seconds * (toM / fromM).pow(RIEGEL_EXPONENT)

    /** Daniels/Gilbert VO2max-equivalent for a race effort. */
    fun vdot(distanceM: Double, seconds: Double): Double {
        val minutes = seconds / 60
        val velocity = distanceM / minutes
        val vo2 = -4.60 + 0.182258 * velocity + 0.000104 * velocity * velocity
        val fraction = 0.8 + 0.1894393 * exp(-0.012778 * minutes) + 0.2989558 * exp(-0.1932605 * minutes)
        return vo2 / fraction
    }

    /** [efforts] are the best time per standard distance (span = metres, value = seconds). Null when nothing is usable. */
    fun predict(efforts: List<CurvePoint>): Predictions? {
        val basis = efforts.filter { it.span >= MIN_BASIS_DISTANCE && it.value >= MIN_BASIS_SECONDS }
        if (basis.isEmpty()) return null
        val out = TARGETS.map { (target, name) ->
            val near = basis.filter { it.span / target in 1 / COMPARABLE_RATIO..COMPARABLE_RATIO }
            val pool = near.ifEmpty { listOf(basis.minBy { abs(ln(it.span / target)) }) }
            val best = pool.minBy { riegel(it.value, it.span, target) }
            val seconds = riegel(best.value, best.span, target)
            Prediction(target, name, seconds, target / seconds, best.span == target, best)
        }
        val scored = basis.filter { it.value in VDOT_MIN_SECONDS..VDOT_MAX_SECONDS }.map { vdot(it.span, it.value) to it }
        val top = scored.maxByOrNull { it.first }
        return Predictions(out, top?.first, top?.second)
    }
}

class CsTrendPoint(val date: LocalDate, val cs: Double, val dPrime: Double)

class CsZone(val zone: Int, val name: String, val low: Double, val high: Double?)

class CsPrediction(val distance: Double, val name: String, val seconds: Double, val speed: Double)

/** The rest of the PC's `critical_speed.analysis`: zone bounds, the monthly trend and the mile and 5K predictions. */
object CriticalSpeedReport {
    val PREDICTED = listOf(1609.344 to "Mile", 5000.0 to "5K")

    fun zoneBounds(cs: Double): List<CsZone> {
        val lows = CriticalSpeed.ZONES.map { it.second * cs }
        return CriticalSpeed.ZONES.mapIndexed { i, (name, _) -> CsZone(i + 1, name, lows[i], lows.getOrNull(i + 1)) }
    }

    /** CS at the end of each month (or today for the current one) that has a fit. */
    fun monthlyTrend(efforts: List<SpeedEffort>, today: LocalDate): List<CsTrendPoint> {
        if (efforts.isEmpty()) return emptyList()
        val out = ArrayList<CsTrendPoint>()
        var month = efforts.first().date.withDayOfMonth(1)
        while (!month.isAfter(today.withDayOfMonth(1))) {
            val end = month.plusMonths(1).minusDays(1)
            CriticalSpeed.fitWindow(efforts, if (end.isBefore(today)) end else today)?.let { out += CsTrendPoint(it.asOf, it.cs, it.dPrime) }
            month = month.plusMonths(1)
        }
        return out
    }

    /** Mile and 5K times from the model, t = (D - D') / CS. Longer distances are left to the Riegel predictor. */
    fun predict(cs: Double, dPrime: Double): List<CsPrediction> = PREDICTED.filter { it.first > dPrime }.map { (d, name) ->
        val seconds = (d - dPrime) / cs
        CsPrediction(d, name, seconds, d / seconds)
    }
}

/** One week of the run-type mix. */
class WeekMix(val weekStart: LocalDate, val counts: Map<RunType, Int>, val km: Map<RunType, Double>)

object RunTypeMix {
    /** Run types per week (Monday start) over the last [weeks] weeks, zeros included. */
    fun weekly(runs: List<Triple<LocalDate, Double?, RunType>>, today: LocalDate, weeks: Int = 26): List<WeekMix> {
        val first = Volume.weekStart(today).minusWeeks(weeks - 1L)
        val counts = (0 until weeks).associate { first.plusWeeks(it.toLong()) to HashMap<RunType, Int>() }
        val km = (0 until weeks).associate { first.plusWeeks(it.toLong()) to HashMap<RunType, Double>() }
        for ((day, distance, type) in runs) {
            if (day.isBefore(first)) continue
            val w = Volume.weekStart(day)
            val c = counts[w] ?: continue
            c.merge(type, 1, Int::plus)
            km.getValue(w).merge(type, (distance ?: 0.0) / 1000, Double::plus)
        }
        return counts.keys.sorted().map { WeekMix(it, counts.getValue(it), km.getValue(it)) }
    }
}

class RouteRun(val key: String, val date: LocalDate, val distance: Double?, val duration: Double?, val speed: Double?, val avgHr: Double?, val efficiency: Double?)

class RouteTrend(val speedPctPerYear: Double?, val hrBpmPerYear: Double?)

class RouteReport(
    val key: String, val runs: List<RouteRun>, val firstDate: LocalDate, val lastDate: LocalDate, val distance: Double,
    val best: RouteRun?, val medianSpeed: Double?, val trend: RouteTrend, val polylines: List<String>,
)

/** The most-run routes with their runs over time and a trend in speed and heart rate (PC: `route_match.routes`). */
object RouteProgress {
    const val MIN_TREND_RUNS = 8
    const val MIN_TREND_DAYS = 90

    class Input(val key: String, val date: LocalDate, val distance: Double?, val duration: Double?, val hrHist: Map<Int, Double>?, val efficiency: Double?, val polylines: List<String>)

    fun avgHr(hist: Map<Int, Double>?): Double? {
        if (hist.isNullOrEmpty()) return null
        val total = hist.values.sum()
        return if (total != 0.0) hist.entries.sumOf { it.key * it.value } / total else null
    }

    /** [runs] in start order, only those with a track. */
    fun routes(runs: List<Input>, minRuns: Int = RouteMatch.MIN_RUNS, limit: Int = 20): List<RouteReport> {
        val byKey = runs.associateBy { it.key }
        val signatures = RouteMatch.signatures(runs.associate { it.key to RouteMatch.track(it.polylines) })
        val out = ArrayList<RouteReport>()
        for (g in RouteMatch.group(signatures, runs.map { it.key })) {
            if (g.size < minRuns) continue
            val rs = g.map { k ->
                val r = byKey.getValue(k)
                val speed = if (r.distance != null && r.distance != 0.0 && r.duration != null && r.duration != 0.0) r.distance / r.duration else null
                RouteRun(k, r.date, r.distance, r.duration, speed, avgHr(r.hrHist), r.efficiency)
            }
            val speeds = rs.mapNotNull { it.speed?.takeIf { s -> s != 0.0 } }
            val best = rs.filter { it.speed != null && it.speed != 0.0 }.maxByOrNull { it.speed!! }
            val span = rs.last().date.toEpochDay() - rs.first().date.toEpochDay()
            var speedTrend: Double? = null
            var hrTrend: Double? = null
            if (rs.size >= MIN_TREND_RUNS && span >= MIN_TREND_DAYS && speeds.isNotEmpty()) {
                val ok = rs.filter { it.speed != null && it.speed != 0.0 }
                val days = ok.map { it.date.toEpochDay().toDouble() }
                speedTrend = Stats.slope(days, ok.map { it.speed!! }) * 365.25 / speeds.average() * 100
                val hr = ok.indices.filter { ok[it].avgHr != null && ok[it].avgHr != 0.0 }
                if (hr.size >= MIN_TREND_RUNS) hrTrend = Stats.slope(hr.map { days[it] }, hr.map { ok[it].avgHr!! }) * 365.25
            }
            val distances = rs.mapNotNull { it.distance?.takeIf { d -> d != 0.0 } }
            out += RouteReport(
                g[0], rs, rs.first().date, rs.last().date, if (distances.isEmpty()) 0.0 else Stats.median(distances),
                best, if (speeds.isEmpty()) null else Stats.median(speeds), RouteTrend(speedTrend, hrTrend), byKey.getValue(g[0]).polylines,
            )
        }
        return out.sortedByDescending { it.runs.size }.take(limit)
    }
}
