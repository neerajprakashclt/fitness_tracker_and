package app.fitness.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** A route reduced to [RouteMatch.SIGNATURE_POINTS] points spaced evenly along it, in local metres. */
class RouteSignature(val x: DoubleArray, val y: DoubleArray, val length: Double)

/**
 * Same-route grouping (PC: `route_match.py`). Each run's simplified track is resampled to a fixed number of points spaced
 * evenly along its length. Two runs are the same route when they start close together, have similar lengths, and their
 * corresponding points stay close the whole way; direction matters. Runs are grouped greedily in date order against each
 * group's first run, so a group is defined by one concrete run, not an average.
 */
object RouteMatch {
    const val SIGNATURE_POINTS = 32
    const val MIN_POINTS = 2
    const val MIN_LENGTH = 800.0
    const val START_TOLERANCE = 200.0
    const val LENGTH_TOLERANCE = 0.15
    const val MEAN_TOLERANCE = 80.0
    const val MAX_TOLERANCE = 250.0
    const val EARTH_RADIUS = 6_371_000.0
    const val MIN_RUNS = 3

    /** Every point of a run's track, segments joined, as (lat, lon). */
    fun track(polylines: List<String>): List<DoubleArray> = polylines.flatMap { Polyline.decode(it) }

    /** Signatures for the tracks long enough to compare, by key. All are projected around the mean of all tracks. */
    fun signatures(tracks: Map<String, List<DoubleArray>>): Map<String, RouteSignature> {
        val usable = tracks.filterValues { it.size >= MIN_POINTS }
        if (usable.isEmpty()) return emptyMap()
        val lat0 = usable.values.map { t -> t.sumOf { it[0] } / t.size }.average()
        val lon0 = usable.values.map { t -> t.sumOf { it[1] } / t.size }.average()
        val k = Math.toRadians(1.0) * EARTH_RADIUS
        val cosLat = cos(Math.toRadians(lat0))
        val out = LinkedHashMap<String, RouteSignature>()
        for ((key, pts) in usable) {
            val x = DoubleArray(pts.size) { (pts[it][1] - lon0) * cosLat * k }
            val y = DoubleArray(pts.size) { (pts[it][0] - lat0) * k }
            val sig = resample(x, y)
            if (sig.length >= MIN_LENGTH) out[key] = sig
        }
        return out
    }

    private fun resample(x: DoubleArray, y: DoubleArray): RouteSignature {
        val cum = DoubleArray(x.size)
        for (i in 1 until x.size) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        val length = cum.last()
        val rx = DoubleArray(SIGNATURE_POINTS)
        val ry = DoubleArray(SIGNATURE_POINTS)
        var j = 0
        for (i in 0 until SIGNATURE_POINTS) {
            val t = length * i / (SIGNATURE_POINTS - 1)
            while (j < cum.size - 2 && cum[j + 1] < t) j++
            val span = cum[j + 1] - cum[j]
            val f = if (span > 0) ((t - cum[j]) / span).coerceIn(0.0, 1.0) else 0.0
            rx[i] = x[j] + (x[j + 1] - x[j]) * f
            ry[i] = y[j] + (y[j + 1] - y[j]) * f
        }
        return RouteSignature(rx, ry, length)
    }

    /** Mean point distance if the two signatures are the same route, else null. */
    fun matches(a: RouteSignature, b: RouteSignature): Double? {
        if (hypot(a.x[0] - b.x[0], a.y[0] - b.y[0]) > START_TOLERANCE) return null
        if (abs(b.length - a.length) > LENGTH_TOLERANCE * a.length) return null
        var max = 0.0
        var sum = 0.0
        for (i in 0 until SIGNATURE_POINTS) {
            val g = hypot(a.x[i] - b.x[i], a.y[i] - b.y[i])
            if (g > max) max = g
            sum += g
        }
        val mean = sum / SIGNATURE_POINTS
        return if (max > MAX_TOLERANCE || mean > MEAN_TOLERANCE) null else mean
    }

    /** Cluster keys (given oldest first). Each group's first key is its reference. */
    fun group(signatures: Map<String, RouteSignature>, order: List<String>): List<List<String>> {
        val groups = ArrayList<MutableList<String>>()
        val refs = ArrayList<RouteSignature>()
        for (key in order) {
            val sig = signatures[key] ?: continue
            var best = -1
            var bestScore = Double.POSITIVE_INFINITY
            for (i in refs.indices) {
                val ref = refs[i]
                if (hypot(ref.x[0] - sig.x[0], ref.y[0] - sig.y[0]) > START_TOLERANCE) continue
                if (abs(sig.length - ref.length) > LENGTH_TOLERANCE * ref.length) continue
                val score = matches(ref, sig) ?: continue
                if (score < bestScore) { best = i; bestScore = score }
            }
            if (best < 0) { groups += mutableListOf(key); refs += sig } else groups[best] += key
        }
        return groups
    }
}
