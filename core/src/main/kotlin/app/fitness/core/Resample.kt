package app.fitness.core

import kotlin.math.round

/** Port of the PC app's `analytics/streams.py`. */
object Resample {
    /** Shorter dropouts are forward-filled; longer gaps are pauses. */
    const val GAP_FILL_LIMIT = 5

    /**
     * Values at 1 Hz with pauses removed, so position i is roughly i seconds of moving time. Rolling-window maths (normalized
     * power, best efforts) then works on plain offsets. Short gaps are forward-filled, or linearly interpolated for cumulative
     * values like distance: devices with "smart recording" sample only every few seconds. NaN means "no sample".
     */
    fun oneHz(time: DoubleArray, values: DoubleArray, fillLimit: Int = GAP_FILL_LIMIT, interpolate: Boolean = false): DoubleArray {
        val n = minOf(time.size, values.size)
        // pandas: round half to even, then keep the first row of each second (even if its value is missing).
        val bySecond = HashMap<Int, Double>(n * 2)
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (i in 0 until n) {
            if (time[i].isNaN()) continue
            val t = round(time[i]).toInt()
            if (bySecond.putIfAbsent(t, values[i]) == null) {
                if (t < lo) lo = t
                if (t > hi) hi = t
            }
        }
        if (bySecond.isEmpty()) return DoubleArray(0)

        val grid = DoubleArray(hi - lo + 1) { bySecond[lo + it] ?: Double.NaN }
        if (interpolate) interpolateInside(grid, fillLimit) else forwardFill(grid, fillLimit)
        return grid.filter { !it.isNaN() }.toDoubleArray()
    }

    private fun forwardFill(g: DoubleArray, limit: Int) {
        var last = Double.NaN
        var run = 0
        for (i in g.indices) {
            if (!g[i].isNaN()) { last = g[i]; run = 0 }
            else if (!last.isNaN() && run < limit) { g[i] = last; run++ }
            else run++
        }
    }

    /** Fills at most [limit] samples at the start of each gap, and only between two real samples. */
    private fun interpolateInside(g: DoubleArray, limit: Int) {
        var prev = -1
        for (i in g.indices) {
            if (g[i].isNaN()) continue
            if (prev >= 0 && i - prev > 1) {
                val a = g[prev]
                val b = g[i]
                for (k in 1..minOf(limit, i - prev - 1)) g[prev + k] = a + (b - a) * k / (i - prev)
            }
            prev = i
        }
    }
}
