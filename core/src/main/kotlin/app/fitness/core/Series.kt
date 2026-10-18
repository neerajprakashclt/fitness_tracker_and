package app.fitness.core

/** x/y pairs ready to chart. */
class Series(val x: DoubleArray, val y: DoubleArray) {
    val size get() = x.size
    fun isEmpty() = x.isEmpty()
}

/** Reduces per-second streams to something a phone chart can draw, without touching the full data used for maths. */
object ChartSeries {
    const val MAX_POINTS = 400

    /**
     * Averages [values] over equal buckets of samples, skipping NaN and samples that fail [keep]. The x of a bucket is the
     * mean time of its kept samples, so a pause leaves a visible gap rather than a stretched line.
     */
    fun bucketMean(time: DoubleArray, values: DoubleArray, maxPoints: Int = MAX_POINTS, keep: (Double) -> Boolean = { true }): Series {
        val n = minOf(time.size, values.size)
        val size = maxOf(1, (n + maxPoints - 1) / maxPoints)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var i = 0
        while (i < n) {
            var sx = 0.0
            var sy = 0.0
            var c = 0
            for (j in i until minOf(i + size, n)) {
                val v = values[j]
                if (!v.isNaN() && keep(v) && !time[j].isNaN()) { sx += time[j]; sy += v; c++ }
            }
            // Whole seconds: the chart library rejects x-values with more than four decimals (a bucket mean like 1234.3333).
            if (c > 0) { xs += Math.rint(sx / c); ys += sy / c }
            i += size
        }
        return Series(xs.toDoubleArray(), ys.toDoubleArray())
    }

    /** Clamp to the [lowQ, highQ] quantiles, so one GPS glitch cannot flatten a whole pace chart (design log D8). */
    fun clipToQuantiles(y: DoubleArray, lowQ: Double = 0.02, highQ: Double = 0.98): DoubleArray {
        if (y.size < 10) return y
        val sorted = y.sortedArray()
        val lo = sorted[((sorted.size - 1) * lowQ).toInt()]
        val hi = sorted[((sorted.size - 1) * highQ).toInt()]
        return DoubleArray(y.size) { y[it].coerceIn(lo, hi) }
    }

    /** Index of the first sample at or after [t] in an ascending time array, or the last index. */
    fun indexAtTime(time: DoubleArray, t: Double): Int {
        if (time.isEmpty()) return -1
        var lo = 0
        var hi = time.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (time[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
