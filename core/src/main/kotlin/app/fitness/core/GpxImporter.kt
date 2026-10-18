package app.fitness.core

import java.time.Instant

/**
 * GPX file to [ParsedActivity]. Port of `app/importers/gpx_importer.py`. GPX carries position, elevation, time and (via
 * extensions) HR/cadence/power, so speed, distance and moving time are derived here. There are no laps.
 */
object GpxImporter {
    private const val SPEED_WINDOW = 2          // samples either side for the displayed (centred) speed

    private class Point(val time: Instant, val lat: Double, val lon: Double, val ele: Double?, val ext: Map<String, Double>)

    /** Flatten e.g. `<gpxtpx:TrackPointExtension><gpxtpx:hr>150</...>` into {hr: 150.0} (PC: `_extensions`). */
    private fun extensions(pt: XmlNode): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (extensions in pt.childrenNamed("extensions")) for (ext in extensions.children) for (el in ext.descendantsAndSelf()) {
            el.number()?.let { out[el.name.lowercase()] = it }
        }
        return out
    }

    fun parse(bytes: ByteArray): ParsedActivity {
        val root = XmlNode.parse(bytes.trimLeadingWhitespace())
        val tracks = root.childrenNamed("trk")
        val points = tracks.flatMap { it.childrenNamed("trkseg") }.flatMap { it.childrenNamed("trkpt") }.mapNotNull { pt ->
            val time = Times.parse(pt.child("time")?.text) ?: return@mapNotNull null
            Point(time, pt.attrs.getValue("lat").toDouble(), pt.attrs.getValue("lon").toDouble(),
                pt.child("ele")?.number(), extensions(pt))
        }.sortedBy { it.time }          // stable, like Python's sort
        if (points.isEmpty()) error("GPX has no timestamped track points (a planned route, or an activity recorded without GPS)")

        val first = points[0].time
        val t = DoubleArray(points.size) { Times.seconds(first, points[it].time) }
        val lat = DoubleArray(points.size) { points[it].lat }
        val lon = DoubleArray(points.size) { points[it].lon }
        val dist = Common.pathDistance(lat, lon)
        val speed = Common.centredSpeed(t, dist, SPEED_WINDOW)
        val moving = Common.movingTime(t, dist)

        fun ext(key: String) = DoubleArray(points.size) { points[it].ext[key] ?: Double.NaN }
        val altitude = DoubleArray(points.size) { points[it].ele ?: Double.NaN }
        val streams = Common.keepStreams(mapOf(
            "time" to t, "heart_rate" to ext("hr"), "power" to ext("power"), "speed" to speed, "cadence" to ext("cad"),
            "altitude" to altitude, "distance" to dist, "lat" to lat, "lon" to lon,
        ))

        return ParsedActivity(
            sport = Common.normalizeSport(tracks.firstNotNullOfOrNull { it.child("type")?.text?.takeIf { s -> s.isNotEmpty() } }),
            startTime = first,
            duration = if (moving != 0.0) moving else t.last(),
            distance = dist.last(),
            elevationGain = Common.elevationGain(altitude),
            device = root.attrs["creator"],
            streams = streams,
            laps = emptyList(),
        )
    }
}

/** Some old exports have blank space before the `<?xml` declaration, which strict XML forbids. */
internal fun ByteArray.trimLeadingWhitespace(): ByteArray {
    var i = 0
    while (i < size && (this[i] == ' '.code.toByte() || this[i] == '\n'.code.toByte() || this[i] == '\r'.code.toByte() || this[i] == '\t'.code.toByte())) i++
    return if (i == 0) this else copyOfRange(i, size)
}
