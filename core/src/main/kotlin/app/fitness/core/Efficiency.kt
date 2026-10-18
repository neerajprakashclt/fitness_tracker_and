package app.fitness.core

import java.time.LocalDate
import kotlin.math.round

class EfficiencyMetrics(val efficiency: Double?, val basis: String?, val decoupling: Double?)

class EfficiencyPoint(val key: String, val date: LocalDate, val efficiency: Double, val decoupling: Double?, val duration: Double?, val distance: Double?)

class EfficiencySeries(val sports: List<String>, val sport: String?, val basis: String?, val points: List<EfficiencyPoint>, val trendPctPerMonth: Double?)

/**
 * Aerobic efficiency (PC: `efficiency.py`): output per heartbeat. Efficiency factor is average output over average heart
 * rate (m/min per bpm for speed, watts per bpm for power). Decoupling compares the first and second half of the activity:
 * (EF1 - EF2) / EF1 as a percentage; under about 5% is a well-aerobic effort.
 */
object Efficiency {
    /** Shorter efforts are too noisy to compare. */
    const val MIN_EFFICIENCY_SECONDS = 1200
    /** m/s; below this you are standing, not moving. */
    const val MIN_MOVING_SPEED = 0.5
    const val MIN_TREND_POINTS = 3
    const val MIN_TREND_DAYS = 14

    /**
     * Several streams on one shared 1 Hz moving-time index (PC: `align_1hz`): short gaps are filled forward per stream, and a
     * row survives only if every stream has a value, so the streams stay comparable row by row.
     */
    internal fun align(time: DoubleArray, columns: List<DoubleArray>): List<DoubleArray> {
        val n = columns.minOf { minOf(it.size, time.size) }
        val first = HashMap<Int, Int>(n * 2)
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (i in 0 until n) {
            if (time[i].isNaN()) continue
            val t = round(time[i]).toInt()
            if (first.putIfAbsent(t, i) == null) { if (t < lo) lo = t; if (t > hi) hi = t }
        }
        if (first.isEmpty()) return columns.map { DoubleArray(0) }
        val size = hi - lo + 1
        val grid = columns.map { col ->
            val g = DoubleArray(size) { first[lo + it]?.let { i -> col[i] } ?: Double.NaN }
            var last = Double.NaN
            var run = 0
            for (i in g.indices) {
                if (!g[i].isNaN()) { last = g[i]; run = 0 }
                else if (!last.isNaN() && run < Resample.GAP_FILL_LIMIT) { g[i] = last; run++ }
                else run++
            }
            g
        }
        val keep = (0 until size).filter { i -> grid.none { it[i].isNaN() } }
        return grid.map { g -> DoubleArray(keep.size) { g[keep[it]] } }
    }

    fun metrics(streams: Map<String, DoubleArray>, powerSport: Boolean): EfficiencyMetrics {
        val none = EfficiencyMetrics(null, null, null)
        val time = streams[StreamKeys.TIME] ?: return none
        val hr = streams["heart_rate"] ?: return none
        val usePower = powerSport && streams["power"] != null
        val out = streams[if (usePower) "power" else "speed"] ?: return none

        val (h, o) = align(time, listOf(hr, out)).let { it[0] to it[1] }
        val keep = h.indices.filter { h[it] > 0 && o[it] > (if (usePower) 0.0 else MIN_MOVING_SPEED) }
        if (keep.size < MIN_EFFICIENCY_SECONDS) return none
        val hh = DoubleArray(keep.size) { h[keep[it]] }
        val oo = DoubleArray(keep.size) { o[keep[it]] }
        val scale = if (usePower) 1.0 else 60.0
        val half = keep.size / 2
        val ef1 = oo.take(half).average() / hh.take(half).average()
        val ef2 = oo.drop(half).average() / hh.drop(half).average()
        return EfficiencyMetrics(oo.average() * scale / hh.average(), if (usePower) "power" else "speed", (ef1 - ef2) / ef1 * 100)
    }

    class Row(val key: String, val sport: String, val date: LocalDate, val duration: Double?, val distance: Double?, val efficiency: Double, val basis: String?, val decoupling: Double?)

    /** Efficiency points for one sport (default: the one with the most), oldest first. Rows must be in start order. */
    fun series(rows: List<Row>, sport: String? = null, from: LocalDate? = null, to: LocalDate? = null): EfficiencySeries {
        val counts = LinkedHashMap<String, Int>()
        rows.forEach { counts.merge(it.sport, 1, Int::plus) }
        val sports = counts.keys.sortedByDescending { counts.getValue(it) }
        val chosen = sport ?: sports.firstOrNull() ?: return EfficiencySeries(sports, null, null, emptyList(), null)
        val mine = rows.filter { it.sport == chosen }
        // Only the most common basis: speed-based and power-based EF are different units.
        val basis = mine.groupingBy { it.basis }.eachCount().maxByOrNull { it.value }?.key
        val points = mine.filter { it.basis == basis && (from == null || !it.date.isBefore(from)) && (to == null || !it.date.isAfter(to)) }
            .map { EfficiencyPoint(it.key, it.date, it.efficiency, it.decoupling, it.duration, it.distance) }
        return EfficiencySeries(sports, chosen, basis, points, trend(points))
    }

    /** Least-squares change in EF as a percentage of its mean, per 30 days. */
    private fun trend(points: List<EfficiencyPoint>): Double? {
        if (points.size < MIN_TREND_POINTS) return null
        val days = points.map { it.date.toEpochDay().toDouble() }
        if (days.max() - days.min() < MIN_TREND_DAYS) return null
        val ef = points.map { it.efficiency }
        return Stats.slope(days, ef) * 30 / ef.average() * 100
    }
}

internal object Stats {
    /** Ordinary least-squares slope of y on x. */
    fun slope(x: List<Double>, y: List<Double>): Double {
        val xm = x.average()
        val ym = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in x.indices) { sxy += (x[i] - xm) * (y[i] - ym); sxx += (x[i] - xm) * (x[i] - xm) }
        return sxy / sxx
    }

    fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
