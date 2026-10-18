package app.fitness.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/** Per-activity summaries that do not depend on the user's settings (PC: `stream_metrics`). */
class StreamMetrics(
    val normalizedPower: Double? = null,
    val powerSeconds: Double? = null,
    val best20minPower: Double? = null,
    val hrMax: Double? = null,
    /** Seconds spent at each whole bpm. */
    val hrHist: Map<Int, Double>? = null,
    /** Seconds moving at each tenth of a m/s. */
    val speedHist: Map<Int, Double>? = null,
    /** Candidate interval reps; only worked out for running. */
    val reps: List<Rep>? = null,
)

enum class LoadMethod(val label: String) { POWER("power"), HR("hr"), DURATION("duration") }

class ActivityLoad(val load: Double, val method: LoadMethod)

class Thresholds(val ftp: Double?, val maxHr: Double?, val restingHr: Double?, val thresholdHr: Double?)

enum class SettingSource { SET, ESTIMATED, DEFAULT }

class Setting(val value: Double?, val source: SettingSource?)

class EffectiveSettings(val ftp: Setting, val maxHr: Setting, val restingHr: Setting, val thresholdHr: Setting) {
    val values get() = Thresholds(ftp.value, maxHr.value, restingHr.value, thresholdHr.value)
}

class DayLoad(val date: LocalDate, val load: Double, val ctl: Double, val atl: Double, val tsb: Double)

/** Port of the PC app's `analytics/training_load.py`. Scores are all on the TSS scale (100 = one hour at threshold). */
object TrainingLoad {
    val POWER_SPORTS = setOf("cycling", "e_biking")
    const val CTL_DAYS = 42
    const val ATL_DAYS = 7
    const val DEFAULT_RESTING_HR = 60.0
    const val THRESHOLD_HRR_FRACTION = 0.85
    const val FTP_FROM_20MIN = 0.95
    const val TRIMP_B = 1.92
    const val DURATION_LOAD_PER_HOUR = 50.0
    /** Seconds; longer gaps between samples are pauses. */
    const val MAX_SAMPLE_GAP = 10.0

    const val KEY_FTP = "ftp"
    const val KEY_MAX_HR = "max_hr"
    const val KEY_RESTING_HR = "resting_hr"
    const val KEY_THRESHOLD_HR = "threshold_hr"
    val SETTING_KEYS = listOf(KEY_FTP, KEY_MAX_HR, KEY_RESTING_HR, KEY_THRESHOLD_HR)

    fun streamMetrics(streams: Map<String, DoubleArray>, sport: String? = null): StreamMetrics {
        val time = streams[StreamKeys.TIME] ?: return StreamMetrics()
        if (time.isEmpty()) return StreamMetrics()
        var np: Double? = null
        var powerSeconds: Double? = null
        var best20: Double? = null
        streams["power"]?.let { raw ->
            val p = Resample.oneHz(time, raw)
            if (p.size >= 30) {
                val rolling = rollingMeans(p, 30)
                np = (rolling.sumOf { it.pow(4) } / rolling.size).pow(0.25)
                powerSeconds = p.size.toDouble()
            }
            if (p.size >= 1200) best20 = rollingMeans(p, 1200).max()
        }

        var hist: Map<Int, Double>? = null
        var hrMax: Double? = null
        streams["heart_rate"]?.let { hr ->
            val h = HashMap<Int, Double>()
            for (i in hr.indices) {
                val bpm = hr[i]
                if (bpm.isNaN() || bpm <= 0) continue
                val dt = if (i + 1 < time.size) time[i + 1] - time[i] else 1.0
                if (dt > 0 && dt <= MAX_SAMPLE_GAP) h.merge(round(bpm).toInt(), dt, Double::plus)
            }
            if (h.isNotEmpty()) {
                hist = h
                hrMax = percentile(hr.filter { !it.isNaN() && it > 0 }.sorted(), 99.0)
            }
        }
        val reps = if (sport == "running") RunProfile.detectReps(time, streams["speed"]).takeIf { it.isNotEmpty() } else null
        return StreamMetrics(np, powerSeconds, best20, hrMax, hist, RunProfile.speedHistogram(time, streams["speed"]), reps)
    }

    /** Means of every full window, like pandas `rolling(w).mean().dropna()`. */
    internal fun rollingMeans(x: DoubleArray, w: Int): DoubleArray {
        val out = DoubleArray(x.size - w + 1)
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= w) sum -= x[i - w]
            if (i >= w - 1) out[i - w + 1] = sum / w
        }
        return out
    }

    /** numpy's default (linear) percentile of an already sorted list. */
    internal fun percentile(sorted: List<Double>, q: Double): Double {
        val pos = q / 100 * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /**
     * What each threshold is right now: the value the user set, or an estimate from the data. [maxHrSeen] is the highest
     * per-activity hr_max and [best20Cycling] the best 20-minute power in power sports; [stored] are the user's own values.
     */
    fun effectiveSettings(stored: Map<String, Double>, maxHrSeen: Double?, best20Cycling: Double?): EffectiveSettings {
        fun pick(key: String, estimate: Double?, source: SettingSource = SettingSource.ESTIMATED): Setting = when {
            key in stored -> Setting(stored.getValue(key), SettingSource.SET)
            estimate != null -> Setting(estimate, source)
            else -> Setting(null, null)
        }
        val maxHr = pick(KEY_MAX_HR, maxHrSeen?.let { round(it) })
        val resting = pick(KEY_RESTING_HR, DEFAULT_RESTING_HR, SettingSource.DEFAULT)
        val mx = maxHr.value
        val rest = resting.value!!
        val threshold = pick(KEY_THRESHOLD_HR, mx?.let { round(rest + THRESHOLD_HRR_FRACTION * (it - rest)) })
        val ftp = pick(KEY_FTP, best20Cycling?.let { round(it * FTP_FROM_20MIN) })
        return EffectiveSettings(ftp, maxHr, resting, threshold)
    }

    private fun trimpWeight(hrr: Double) = hrr * 0.64 * exp(TRIMP_B * hrr)

    /** The load score for one activity, by the best method its data and the settings allow. */
    fun activityLoad(sport: String, duration: Double?, m: StreamMetrics, s: Thresholds): ActivityLoad {
        val ftp = s.ftp
        val np = m.normalizedPower
        if (sport in POWER_SPORTS && np != null && np != 0.0 && ftp != null && ftp != 0.0 && m.powerSeconds != null) {
            return ActivityLoad(m.powerSeconds * (np / ftp).pow(2) / 3600 * 100, LoadMethod.POWER)
        }
        val maxHr = s.maxHr
        val lthr = s.thresholdHr
        val rest = s.restingHr
        val hist = m.hrHist
        if (hist != null && hist.isNotEmpty() && maxHr != null && lthr != null && rest != null && rest < lthr && lthr < maxHr) {
            val reserve = maxHr - rest
            val trimp = hist.entries.sumOf { (bpm, secs) -> secs / 60 * trimpWeight(((bpm - rest) / reserve).coerceIn(0.0, 1.0)) }
            val perHourAtThreshold = 60 * trimpWeight((lthr - rest) / reserve)
            return ActivityLoad(trimp / perHourAtThreshold * 100, LoadMethod.HR)
        }
        return ActivityLoad((duration ?: 0.0) / 3600 * DURATION_LOAD_PER_HOUR, LoadMethod.DURATION)
    }

    /** Days are bucketed in the phone's local time zone: a 23:30 run counts for that day. */
    fun localDate(start: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate = start.atZone(zone).toLocalDate()

    /** Fitness (CTL), fatigue (ATL) and form (TSB) for every day from the first activity through [today]. */
    fun dailySeries(perDay: Map<LocalDate, Double>, today: LocalDate): List<DayLoad> {
        if (perDay.isEmpty()) return emptyList()
        var day = perDay.keys.min()
        val last = maxOf(perDay.keys.max(), today)
        var ctl = 0.0
        var atl = 0.0
        val out = ArrayList<DayLoad>()
        while (!day.isAfter(last)) {
            val tsb = ctl - atl // TrainingPeaks: today's form is yesterday's fitness minus fatigue
            val load = perDay[day] ?: 0.0
            ctl += (load - ctl) / CTL_DAYS
            atl += (load - atl) / ATL_DAYS
            out += DayLoad(day, load, ctl, atl, tsb)
            day = day.plusDays(1)
        }
        return out
    }
}
