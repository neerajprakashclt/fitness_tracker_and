package app.fitness.core

import java.util.Locale

enum class Units { METRIC, IMPERIAL }

/**
 * Display formatting, ported from the PC app's `static/common.js`. Data is always stored in metric (m, m/s, seconds);
 * only display converts. Pace ("m:ss /km") for running, walking and hiking; km/h for everything else.
 */
class Formatter(val units: Units = Units.METRIC) {
    private val imperial get() = units == Units.IMPERIAL

    val distUnit get() = if (imperial) "mi" else "km"
    val elevUnit get() = if (imperial) "ft" else "m"
    val speedUnit get() = if (imperial) "mph" else "km/h"

    fun toDist(m: Double) = m / (if (imperial) M_PER_MILE else 1000.0)
    fun toElev(m: Double) = if (imperial) m / M_PER_FOOT else m
    fun toSpeed(mps: Double) = mps * (if (imperial) 3600 / M_PER_MILE else 3.6)

    /** Minutes per km or mile from m/s. */
    fun toPaceMin(mps: Double) = (if (imperial) M_PER_MILE else 1000.0) / mps / 60

    fun distance(m: Double?): String = if (m == null) DASH else fixed(toDist(m), 2) + " " + distUnit

    fun elevation(m: Double?): String = if (m == null) DASH else "%,d".format(Locale.ROOT, jsRound(toElev(m)).toInt()) + " " + elevUnit

    /** "5:39" from m/s, with no unit. */
    fun paceBare(speed: Double?): String {
        if (speed == null || speed.isNaN() || speed < 0.3) return DASH
        val s = jsRound(toPaceMin(speed) * 60).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun pace(speed: Double?): String = paceBare(speed).let { if (it == DASH) it else "$it /$distUnit" }

    fun speed(mps: Double?): String = if (mps == null) DASH else fixed(toSpeed(mps), 1) + " " + speedUnit

    /** Pace for the foot sports and speed for the rest, the way the PC app shows an activity's average. */
    fun paceOrSpeed(sport: String, mps: Double?): String = if (sport in PACE_SPORTS) pace(mps) else speed(mps)

    fun paceOrSpeedLabel(sport: String) = if (sport in PACE_SPORTS) "Pace" else "Speed"

    companion object {
        const val M_PER_MILE = 1609.344
        const val M_PER_FOOT = 0.3048
        const val DASH = "–"
        val PACE_SPORTS = setOf("running", "walking", "hiking")

        private fun fixed(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)

        /** JavaScript's `Math.round`: halves go up. */
        private fun jsRound(v: Double) = Math.floor(v + 0.5)

        /** "1:02:03" or "5:09". */
        fun duration(sec: Double?): String {
            if (sec == null) return DASH
            val total = jsRound(sec).toInt()
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            val mm = m.toString().padStart(if (h > 0) 2 else 1, '0')
            val ss = s.toString().padStart(2, '0')
            return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
        }

        /** Long totals: "12h 05m". */
        fun hours(sec: Double?): String {
            if (sec == null || sec == 0.0) return DASH
            val total = jsRound(sec / 60).toInt()            // round once, so 2h 59.6m becomes 3h 00m, never "2h 60m"
            val h = total / 60
            val m = total % 60
            return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
        }

        /** "running" -> "running", "trail_running" -> "trail running". */
        fun sport(s: String?): String = (s ?: "unknown").replace('_', ' ')
    }
}
