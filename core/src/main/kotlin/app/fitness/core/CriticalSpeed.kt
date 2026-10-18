package app.fitness.core

import java.time.LocalDate

/** The best speeds one activity set at the model's durations. */
class SpeedEffort(val date: LocalDate, val speeds: Map<Int, Double>)

class CriticalSpeedFit(val cs: Double, val dPrime: Double, val points: Int, val note: String?, val r2: Double, val windowDays: Int, val asOf: LocalDate)

/** Port of the PC's `critical_speed.py`: the pace you could hold for a very long time, from your best 3 to 20 minute efforts. */
object CriticalSpeed {
    val CS_DURATIONS = listOf(180, 240, 300, 420, 600, 900, 1200)
    const val MIN_DURATIONS = 4
    const val WINDOW_DAYS = 90
    const val FALLBACK_WINDOW_DAYS = 180
    const val MAX_D_PRIME = 800.0
    /** Zones as a fraction of critical speed. */
    val ZONES = listOf("Easy" to 0.0, "Steady" to 0.80, "Threshold" to 0.90, "Interval" to 1.00, "Repetition" to 1.10)

    /** One [SpeedEffort] per activity that has any, in start order. */
    fun efforts(entries: List<Curves.Entry>): List<SpeedEffort> = entries.mapNotNull { e ->
        val speeds = CS_DURATIONS.mapNotNull { d -> e.bests.speed[d]?.let { d to it } }.toMap()
        if (speeds.isEmpty()) null else SpeedEffort(e.date, speeds)
    }

    fun bestInWindow(efforts: List<SpeedEffort>, start: LocalDate, end: LocalDate): Map<Int, Double> {
        val best = HashMap<Int, Double>()
        for (e in efforts) if (!e.date.isBefore(start) && !e.date.isAfter(end)) e.speeds.forEach { (d, v) -> best.merge(d, v, ::maxOf) }
        return best
    }

    /** Straight-line fit of distance against time: slope is CS, intercept D'. Null when it cannot be fitted. */
    internal fun fit(best: Map<Int, Double>): Triple<Double, Double, FitStats>? {
        if (best.size < MIN_DURATIONS) return null
        val t = best.keys.sorted().map { it.toDouble() }
        val dist = t.map { it * best.getValue(it.toInt()) }
        val n = t.size
        val tm = t.average()
        val dm = dist.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) { sxy += (t[i] - tm) * (dist[i] - dm); sxx += (t[i] - tm) * (t[i] - tm) }
        var cs = sxy / sxx
        var dPrime = dm - cs * tm
        var note: String? = null
        if (cs <= 0) return null
        if (dPrime < 0) {
            // Short efforts are no faster than the line: no anaerobic reserve shows up. Fit through the origin instead.
            cs = (0 until n).sumOf { t[it] * dist[it] } / (0 until n).sumOf { t[it] * t[it] }
            dPrime = 0.0
            note = "no_reserve"
        } else if (dPrime > MAX_D_PRIME) note = "large_reserve"
        val residual = (0 until n).sumOf { val e = dist[it] - (cs * t[it] + dPrime); e * e }
        val total = dist.sumOf { (it - dm) * (it - dm) }
        return Triple(cs, dPrime, FitStats(n, note, if (total != 0.0) 1 - residual / total else 0.0))
    }

    internal class FitStats(val points: Int, val note: String?, val r2: Double)

    /** Fit over the trailing window ending at [end], widening once if there is not enough data. */
    fun fitWindow(efforts: List<SpeedEffort>, end: LocalDate, days: Int = WINDOW_DAYS): CriticalSpeedFit? {
        for (d in sortedSetOf(days, maxOf(days, FALLBACK_WINDOW_DAYS))) {
            val f = fit(bestInWindow(efforts, end.minusDays(d.toLong()), end)) ?: continue
            return CriticalSpeedFit(f.first, f.second, f.third.points, f.third.note, f.third.r2, d, end)
        }
        return null
    }

    /** Critical speed as of any date, cached, for classifying runs by the fitness of their day. */
    class Timeline(private val efforts: List<SpeedEffort>) {
        private val cache = HashMap<LocalDate, CriticalSpeedFit?>()
        fun at(day: LocalDate): CriticalSpeedFit? = cache.getOrPut(day) { fitWindow(efforts, day) }
    }

    fun zoneIndex(speed: Double, cs: Double): Int {
        var z = 0
        for ((i, f) in ZONES.withIndex()) if (speed >= f.second * cs) z = i
        return z
    }

    /** Seconds in each pace zone, from a {tenths of m/s: seconds} histogram. */
    fun timeInZones(hist: Map<Int, Double>, cs: Double): DoubleArray {
        val out = DoubleArray(ZONES.size)
        for ((tenths, secs) in hist) out[zoneIndex(tenths / 10.0, cs)] += secs
        return out
    }
}

/** Heart-rate zones (PC: `zones.py`). */
object Zones {
    val NAMES = listOf("Recovery", "Endurance", "Tempo", "Threshold", "VO2 max")
    val FLOORS = listOf(0.0, 0.60, 0.70, 0.80, 0.90)

    fun zoneIndex(bpm: Double, maxHr: Double): Int {
        var z = 0
        for ((i, f) in FLOORS.withIndex()) if (bpm >= kotlin.math.round(maxHr * f)) z = i
        return z
    }

    fun timeInZones(hist: Map<Int, Double>, maxHr: Double): DoubleArray {
        val out = DoubleArray(FLOORS.size)
        for ((bpm, secs) in hist) out[zoneIndex(bpm.toDouble(), maxHr)] += secs
        return out
    }
}
