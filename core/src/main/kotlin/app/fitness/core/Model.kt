package app.fitness.core

import java.time.Instant

/** Stream keys, all aligned to `time` (seconds since the activity started). Missing samples are NaN. */
object StreamKeys {
    const val TIME = "time"
    val ALL = listOf("time", "heart_rate", "power", "speed", "cadence", "altitude", "distance", "lat", "lon")

    /** A watch may write 0 for a sensor it does not have, so an all-zero stream here means "no sensor". */
    val SENSORS = setOf("heart_rate", "power", "cadence", "speed")
}

data class ParsedLap(
    val index: Int,
    val startTime: Instant? = null,
    val duration: Double? = null,
    val distance: Double? = null,
    val avgHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val avgPower: Double? = null,
    val avgSpeed: Double? = null,
    val elevationGain: Double? = null,
)

class ParsedActivity(
    val sport: String,
    val startTime: Instant,
    /** Moving time in seconds, as the PC app defines it (FIT: the device timer unless the track shows less movement). */
    val duration: Double?,
    val distance: Double?,
    val elevationGain: Double?,
    val device: String?,
    /** Only streams with data are present; each array is as long as `time`. */
    val streams: Map<String, DoubleArray>,
    val laps: List<ParsedLap>,
)
