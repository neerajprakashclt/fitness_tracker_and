package app.fitness.core

/** Google encoded polyline (precision 5), the format routes are stored in. Port of `decode_polyline`. */
object Polyline {
    /** Decoded points as [lat, lon] pairs. */
    fun decode(encoded: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0
                while (true) {
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1F) shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            out += doubleArrayOf(lat / 1e5, lon / 1e5)
        }
        return out
    }

    /**
     * Decodes [encoded] straight into GeoJSON coordinate text, `[lon,lat],[lon,lat],...` (no brackets around the list),
     * without creating one object per point. Returns the number of points written. Built for the heatmap, which writes
     * well over 100,000 points: formatting integers by hand was far cheaper than `Double.toString` per coordinate.
     */
    fun appendGeoJson(encoded: String, out: StringBuilder): Int {
        var index = 0
        var lat = 0
        var lon = 0
        var n = 0
        while (index < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0
                while (true) {
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1F) shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            if (n > 0) out.append(',')
            out.append('[')
            appendE5(out, lon)
            out.append(',')
            appendE5(out, lat)
            out.append(']')
            n++
        }
        return n
    }

    /** A coordinate held as an integer number of 1e-5 degrees, written as decimal text. */
    private fun appendE5(out: StringBuilder, e5: Int) {
        var v = e5
        if (v < 0) { out.append('-'); v = -v }
        out.append(v / 100000).append('.')
        val frac = v % 100000
        if (frac < 10000) out.append('0')
        if (frac < 1000) out.append('0')
        if (frac < 100) out.append('0')
        if (frac < 10) out.append('0')
        out.append(frac)
    }
}
