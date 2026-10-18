package app.fitness.core

/** Helpers shared by every parser. Ports of `app/importers/common.py`. */
object Common {
    /** Drop streams with no real data (PC: `keep_streams`). */
    fun keepStreams(raw: Map<String, DoubleArray>): Map<String, DoubleArray> =
        raw.filter { (key, values) ->
            if (key in StreamKeys.SENSORS) values.any { !it.isNaN() && it != 0.0 } else values.any { !it.isNaN() }
        }

    /** Total ascent with hysteresis, so sensor noise does not inflate the sum (PC: `elevation_gain`). */
    fun elevationGain(altitude: DoubleArray, threshold: Double = 2.0): Double? {
        val pts = altitude.filter { !it.isNaN() }
        if (pts.size < 2) return null
        var gain = 0.0
        var ref = pts[0]
        for (a in pts.drop(1)) {
            if (a - ref >= threshold) {
                gain += a - ref
                ref = a
            } else if (a < ref) {
                ref = a
            }
        }
        return gain
    }

    private val STRAVA_TYPES = mapOf("1" to "cycling", "9" to "running", "10" to "walking", "4" to "hiking", "2" to "alpine_skiing")
    private val KEYWORDS = listOf("run" to "running", "ride" to "cycling", "cycl" to "cycling", "bik" to "cycling",
        "walk" to "walking", "hik" to "hiking", "swim" to "swimming")

    /** Map free-form sport names ("Ride", "9", "Trail Run") onto the stored names (PC: `normalize_sport`). */
    fun normalizeSport(raw: String?): String {
        if (raw.isNullOrEmpty()) return "unknown"
        val s = raw.trim().lowercase()
        STRAVA_TYPES[s]?.let { return it }
        for ((key, sport) in KEYWORDS) if (key in s) return sport
        return s.replace(" ", "_")
    }

    /** Average pace that separates running from walking: 99.1% right on 650 labelled foot activities. */
    const val FOOT_SPEED = 1.9

    /** "running" or "walking" from average moving pace, "unknown" without both numbers (PC: `infer_foot_sport`). */
    fun inferFootSport(distance: Double?, duration: Double?): String {
        if (distance == null || distance == 0.0 || duration == null || duration <= 0) return "unknown"
        return if (distance / duration >= FOOT_SPEED) "running" else "walking"
    }

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
    }

    /** Cumulative metres along a track. Samples with no position add nothing (PC: `path_distance`). */
    fun pathDistance(lat: DoubleArray, lon: DoubleArray): DoubleArray {
        val out = DoubleArray(lat.size)
        var total = 0.0
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        for (i in lat.indices) {
            if (!lat[i].isNaN() && !lon[i].isNaN()) {
                if (!prevLat.isNaN()) total += haversine(prevLat, prevLon, lat[i], lon[i])
                prevLat = lat[i]
                prevLon = lon[i]
            }
            out[i] = total
        }
        return out
    }

    /** Speed in m/s from cumulative distance, a centred difference over [window] samples either side. NaN when undefined. */
    fun centredSpeed(t: DoubleArray, dist: DoubleArray, window: Int = 2): DoubleArray {
        val n = t.size
        return DoubleArray(n) { i ->
            val lo = maxOf(0, i - window)
            val hi = minOf(n - 1, i + window)
            val dt = t[hi] - t[lo]
            if (dt > 0) (dist[hi] - dist[lo]) / dt else Double.NaN
        }
    }

    const val MOVING_SPEED = 0.3        // m/s; slower than this counts as stopped
    const val MOVING_WINDOW = 3         // samples either side when smoothing speed
    const val MAX_SAMPLE_GAP = 30.0     // s; a longer gap between samples is a pause

    /** Fill NaN gaps with the last known value; cumulative distance has gaps in some files (PC: `ffill`). */
    fun ffill(values: DoubleArray, first: Double = 0.0): DoubleArray {
        var last = first
        return DoubleArray(values.size) { i -> if (!values[i].isNaN()) last = values[i]; last }
    }

    /**
     * Seconds spent moving, from time and cumulative distance (PC: `moving_time`). Speed is smoothed over a few samples,
     * gaps longer than [MAX_SAMPLE_GAP] are pauses, and the smoothing never reaches across one.
     */
    fun movingTime(t: DoubleArray, dist: DoubleArray): Double {
        var total = 0.0
        var start = 0
        for (i in 1..t.size) {
            if (i < t.size && t[i] - t[i - 1] <= MAX_SAMPLE_GAP) continue
            val segT = t.copyOfRange(start, i)
            val segD = dist.copyOfRange(start, i)
            val speed = centredSpeed(segT, segD, MOVING_WINDOW)
            for (j in 1 until segT.size) {
                val dt = segT[j] - segT[j - 1]
                if (dt > 0 && !speed[j].isNaN() && speed[j] >= MOVING_SPEED) total += dt
            }
            start = i
        }
        return total
    }
}
