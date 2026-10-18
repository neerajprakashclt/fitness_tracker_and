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
}
