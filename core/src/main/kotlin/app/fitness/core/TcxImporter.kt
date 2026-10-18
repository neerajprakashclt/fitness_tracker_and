package app.fitness.core

import java.time.Instant

/**
 * TCX (Garmin Training Center XML) file to [ParsedActivity]. Port of `app/importers/tcx_importer.py`. Reads the first
 * `<Activity>`: its laps and every timestamped `<Trackpoint>`. Courses and workouts are rejected. Distance is the
 * track's and duration is moving time derived from it; lap totals are only a fallback (indoor files with no distance).
 */
object TcxImporter {
    private const val SPEED_WINDOW = 2          // samples either side for the displayed (centred) speed

    private class Row(
        val time: Instant, val lat: Double?, val lon: Double?, val alt: Double?, val dist: Double?, val hr: Double?,
        val cad: Double?, val runCad: Double?, val speed: Double?, val power: Double?,
    )

    private fun bpm(parent: XmlNode, name: String): Double? = parent.child(name)?.child("Value")?.number()

    private fun lap(index: Int, lap: XmlNode): ParsedLap {
        val duration = lap.child("TotalTimeSeconds")?.number()
        val distance = lap.child("DistanceMeters")?.number()
        var speed = lap.find("AvgSpeed")?.number()
        if (speed == null && duration != null && duration != 0.0 && distance != null && distance != 0.0) speed = distance / duration
        return ParsedLap(
            index = index,
            startTime = Times.parse(lap.attrs["StartTime"]),
            duration = duration,
            distance = distance,
            avgHeartRate = bpm(lap, "AverageHeartRateBpm"),
            maxHeartRate = bpm(lap, "MaximumHeartRateBpm"),
            avgPower = lap.find("AvgWatts")?.number(),
            avgSpeed = speed,
        )
    }

    fun parse(bytes: ByteArray): ParsedActivity {
        val root = XmlNode.parse(bytes.trimLeadingWhitespace())
        val activity = root.descendantsAndSelf().firstOrNull { it.name == "Activity" }
            ?: error("TCX has no <Activity> (a course or workout, not a recorded activity?)")

        val laps = activity.childrenNamed("Lap").mapIndexed { i, el -> lap(i, el) }

        val parsed = activity.descendantsAndSelf().filter { it.name == "Trackpoint" }.mapNotNull { tp ->
            val time = Times.parse(tp.child("Time")?.text) ?: return@mapNotNull null
            val pos = tp.child("Position")
            Row(time, pos?.child("LatitudeDegrees")?.number(), pos?.child("LongitudeDegrees")?.number(),
                tp.child("AltitudeMeters")?.number(), tp.child("DistanceMeters")?.number(), bpm(tp, "HeartRateBpm"),
                tp.child("Cadence")?.number(), tp.find("RunCadence")?.number(), tp.find("Speed")?.number(), tp.find("Watts")?.number())
        }.toList()
        if (parsed.isEmpty()) error("TCX has no timestamped trackpoints (a course or workout, not a recorded activity?)")

        // Lap boundaries repeat a timestamp; keep the first point at each instant.
        val sorted = parsed.sortedBy { it.time }
        val rows = sorted.filterIndexed { i, r -> i == 0 || r.time != sorted[i - 1].time }
        val n = rows.size
        val t = DoubleArray(n) { Times.seconds(rows[0].time, rows[it].time) }

        fun col(f: (Row) -> Double?) = DoubleArray(n) { f(rows[it]) ?: Double.NaN }
        val lat = col { it.lat }
        val lon = col { it.lon }
        var dist = col { it.dist }
        if (dist.all { it.isNaN() } && lat.any { !it.isNaN() }) dist = Common.pathDistance(lat, lon)
        val complete = Common.ffill(dist)           // gap-free copy, for deriving speed and moving time

        var speed = col { it.speed }
        if (speed.all { it.isNaN() } && complete.last() > 0) speed = Common.centredSpeed(t, complete, SPEED_WINDOW)

        val altitude = col { it.alt }
        val streams = Common.keepStreams(mapOf(
            "time" to t, "heart_rate" to col { it.hr }, "power" to col { it.power }, "speed" to speed,
            "cadence" to col { it.cad ?: it.runCad }, "altitude" to altitude, "distance" to dist, "lat" to lat, "lon" to lon,
        ))

        val lapTimes = laps.map { it.duration }
        val lapDists = laps.map { it.distance }
        val lapDistance = if (laps.isNotEmpty() && lapDists.all { it != null && it != 0.0 }) lapDists.sumOf { it!! } else null
        val distance = complete.last().takeIf { it != 0.0 } ?: lapDistance
        val lapTime = if (laps.isNotEmpty() && lapTimes.all { it != null && it != 0.0 }) lapTimes.sumOf { it!! } else null
        val duration = Common.movingTime(t, complete).takeIf { it != 0.0 } ?: lapTime ?: t.last()

        val creator = activity.child("Creator") ?: root.find("Author")
        return ParsedActivity(
            sport = Common.normalizeSport(activity.attrs["Sport"]),
            startTime = rows[0].time,
            duration = duration,
            distance = distance,
            elevationGain = Common.elevationGain(altitude),
            device = creator?.child("Name")?.text,
            streams = streams,
            laps = laps,
        )
    }
}
