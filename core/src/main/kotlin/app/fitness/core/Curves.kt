package app.fitness.core

/** Best efforts of one activity, from 1 Hz moving-time series. */
class Bests(
    /** Best average power over each duration (seconds). */
    val power: Map<Int, Double> = emptyMap(),
    /** Best average speed (m/s) over each duration: the pace curve. */
    val speed: Map<Int, Double> = emptyMap(),
    /** Fastest time (seconds) to cover each standard distance (metres). */
    val distanceTime: Map<Double, Double> = emptyMap(),
) {
    val isEmpty get() = power.isEmpty() && speed.isEmpty() && distanceTime.isEmpty()
}

/** One point of a curve: the best [value] for [span], and where it was set. */
class CurvePoint(val span: Double, val value: Double, val key: String, val date: java.time.LocalDate)

/** Port of the PC app's `analytics/power_curve.py`. */
object Curves {
    val DURATIONS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 420, 600, 900,
        1200, 1800, 2700, 3600, 5400, 7200, 10800, 14400, 21600)
    /** GPS speed over 1-3 s is too noisy to be a record. */
    const val MIN_SPEED_DURATION = 5
    val PACE_SPORTS = setOf("running", "walking", "hiking")
    val RUN_DISTANCES = listOf(400.0, 1000.0, 1609.34, 5000.0, 10000.0, 16093.4, 21097.5, 42195.0)
    val RIDE_DISTANCES = listOf(5000.0, 10000.0, 20000.0, 40000.0, 50000.0, 100000.0, 160934.0)
    /** Per-second distance jumps above this are GPS glitches, not movement (m/s). */
    const val FOOT_MAX_SPEED = 12.0
    const val DEFAULT_MAX_SPEED = 30.0

    fun distancesFor(sport: String): List<Double> = when (sport) {
        in PACE_SPORTS -> RUN_DISTANCES
        "cycling", "e_biking" -> RIDE_DISTANCES
        else -> emptyList()
    }

    /** Best mean of [x] over each window length, via a cumulative sum. */
    internal fun bestWindowMeans(x: DoubleArray, durations: List<Int>): Map<Int, Double> {
        val c = DoubleArray(x.size + 1)
        for (i in x.indices) c[i + 1] = c[i] + x[i]
        val out = LinkedHashMap<Int, Double>()
        for (w in durations) {
            if (w > x.size) continue
            var best = Double.NEGATIVE_INFINITY
            for (i in w..x.size) best = maxOf(best, (c[i] - c[i - w]) / w)
            out[w] = best
        }
        return out
    }

    private fun cumulativeDistance(streams: Map<String, DoubleArray>, sport: String): DoubleArray? {
        val time = streams.getValue(StreamKeys.TIME)
        val step: DoubleArray = when {
            streams["distance"] != null -> {
                val d = Resample.oneHz(time, streams.getValue("distance"), interpolate = true)
                DoubleArray(d.size) { if (it == 0) 0.0 else d[it] - d[it - 1] }
            }
            streams["speed"] != null -> Resample.oneHz(time, streams.getValue("speed"))
            else -> return null
        }
        // Drop GPS jumps and backwards glitches, then rebuild a monotonic distance.
        val limit = if (sport in PACE_SPORTS) FOOT_MAX_SPEED else DEFAULT_MAX_SPEED
        val out = DoubleArray(step.size + 1)
        for (i in step.indices) {
            val s = step[i]
            out[i + 1] = out[i] + if (s < 0 || s > limit || s.isNaN()) 0.0 else s
        }
        return out
    }

    fun bestEfforts(sport: String, streams: Map<String, DoubleArray>): Bests {
        val time = streams[StreamKeys.TIME]
        if (time == null || time.isEmpty()) return Bests()
        val power = streams["power"]?.let { bestWindowMeans(Resample.oneHz(time, it), DURATIONS) } ?: emptyMap()
        val d = cumulativeDistance(streams, sport)
        if (d == null || d.size <= 1) return Bests(power)

        val perSecond = DoubleArray(d.size - 1) { d[it + 1] - d[it] }
        val speed = bestWindowMeans(perSecond, DURATIONS.filter { it >= MIN_SPEED_DURATION })
        val times = LinkedHashMap<Double, Double>()
        for (target in distancesFor(sport)) {
            if (d.last() < target) continue
            // For each end sample j, the latest start i with d[j] - d[i] >= target.
            var best = Double.POSITIVE_INFINITY
            for (j in d.indices) {
                val start = upperBound(d, d[j] - target) - 1
                if (start >= 0) best = minOf(best, (j - start).toDouble())
            }
            times[target] = best
        }
        return Bests(power, speed, times)
    }

    /** numpy `searchsorted(side="right")`: the first index whose value is greater than [v]. */
    private fun upperBound(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] <= v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    enum class Metric(val higherIsBetter: Boolean) { POWER(true), SPEED(true), DISTANCE_TIME(false) }

    /** One activity's bests, ready to be compared with the others. */
    class Entry(val key: String, val date: java.time.LocalDate, val bests: Bests)

    /** The best effort per span across [entries] (all of one sport), each point saying which activity set it. */
    fun aggregate(entries: List<Entry>, from: java.time.LocalDate? = null, to: java.time.LocalDate? = null): Map<Metric, List<CurvePoint>> {
        val best = Metric.entries.associateWith { LinkedHashMap<Double, CurvePoint>() }
        for (e in entries) {
            if ((from != null && e.date < from) || (to != null && e.date > to)) continue
            fun offer(metric: Metric, span: Double, value: Double) {
                val cur = best.getValue(metric)[span]
                if (cur == null || (if (metric.higherIsBetter) value > cur.value else value < cur.value)) {
                    best.getValue(metric)[span] = CurvePoint(span, value, e.key, e.date)
                }
            }
            e.bests.power.forEach { (s, v) -> offer(Metric.POWER, s.toDouble(), v) }
            e.bests.speed.forEach { (s, v) -> offer(Metric.SPEED, s.toDouble(), v) }
            e.bests.distanceTime.forEach { (s, v) -> offer(Metric.DISTANCE_TIME, s, v) }
        }
        return best.mapValues { (_, spans) -> spans.values.sortedBy { it.span } }
    }
}
