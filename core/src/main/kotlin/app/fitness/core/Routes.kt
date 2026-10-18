package app.fitness.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Routes for the feed thumbnails, the detail map and the heatmap: simplified GPS tracks stored as Google encoded
 * polylines. Port of `app/analytics/heatmap.py`. Each track is split at GPS gaps (so a dropout does not draw a straight
 * line across town), simplified with Ramer-Douglas-Peucker, then encoded.
 */
object Routes {
    /** Bump when simplify/split logic changes so stored routes get rebuilt. */
    const val VERSION = 1

    const val SIMPLIFY_TOLERANCE_M = 5.0        // max deviation of the simplified line from the track
    const val SPLIT_GAP_M = 250.0               // consecutive points further apart than this start a new segment
    const val MIN_SEGMENT_POINTS = 2
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Encoded polylines, one per continuous stretch of GPS track; empty without GPS. */
    fun segments(streams: Map<String, DoubleArray>): List<String> {
        val latAll = streams["lat"] ?: return emptyList()
        val lonAll = streams["lon"] ?: return emptyList()
        val idx = latAll.indices.filter { !latAll[it].isNaN() && !lonAll[it].isNaN() }
        if (idx.size < MIN_SEGMENT_POINTS) return emptyList()
        val lat = DoubleArray(idx.size) { latAll[idx[it]] }
        val lon = DoubleArray(idx.size) { lonAll[idx[it]] }

        // Local equirectangular projection; accurate enough at activity scale.
        val lat0 = Math.toRadians(lat.average())
        val x = DoubleArray(lat.size) { Math.toRadians(lon[it]) * cos(lat0) * EARTH_RADIUS_M }
        val y = DoubleArray(lat.size) { Math.toRadians(lat[it]) * EARTH_RADIUS_M }

        val out = ArrayList<String>()
        var start = 0
        for (i in 1..lat.size) {
            if (i < lat.size && hypot(x[i] - x[i - 1], y[i] - y[i - 1]) <= SPLIT_GAP_M) continue
            if (i - start >= MIN_SEGMENT_POINTS) {
                val kept = simplify(x, y, start, i, SIMPLIFY_TOLERANCE_M)
                out += Polyline.encode(DoubleArray(kept.size) { lat[kept[it]] }, DoubleArray(kept.size) { lon[kept[it]] })
            }
            start = i
        }
        return out
    }

    /** Ramer-Douglas-Peucker on projected points `[from, to)`. Returns the indices to keep. */
    internal fun simplify(x: DoubleArray, y: DoubleArray, from: Int, to: Int, tolerance: Double): IntArray {
        val n = to - from
        if (n < 3) return IntArray(n) { from + it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = x[from + a]; val ay = y[from + a]
            val sx = x[from + b] - ax
            val sy = y[from + b] - ay
            val length = hypot(sx, sy)
            var best = -1.0
            var bestI = 0
            for (k in a + 1 until b) {
                val rx = x[from + k] - ax
                val ry = y[from + k] - ay
                val d = if (length == 0.0) hypot(rx, ry) else abs(sx * ry - sy * rx) / length
                if (d > best) { best = d; bestI = k }          // first maximum, like numpy's argmax
            }
            if (best > tolerance) {
                keep[bestI] = true
                stack.addLast(intArrayOf(a, bestI))
                stack.addLast(intArrayOf(bestI, b))
            }
        }
        return (0 until n).filter { keep[it] }.map { from + it }.toIntArray()
    }
}
