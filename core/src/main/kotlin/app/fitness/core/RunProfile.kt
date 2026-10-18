package app.fitness.core

import kotlin.math.round

/** A stretch of a run well above its own typical speed; whether it is a deliberate interval is decided in [RunTypes]. */
class Rep(val start: Int, val duration: Int, val speed: Double, val recovery: Int?, val recoverySpeed: Double?)

/**
 * Per-activity running profile (PC: `run_profile.py`): a moving-speed histogram and candidate interval reps. Both are
 * settings-independent summaries computed once from the streams; what they mean depends on critical speed, applied later.
 */
object RunProfile {
    /** m/s per histogram bucket; stored as integer tenths of m/s. */
    const val SPEED_BUCKET = 0.1
    /** Slower than this is standing, not running or walking. */
    const val MIN_MOVING_SPEED = 0.3
    const val MIN_PROFILE_SECONDS = 600
    const val SMOOTHING_SECONDS = 5
    val CONTRAST_QUANTILES = doubleArrayOf(0.2, 0.8)
    const val MIN_CONTRAST = 0.30
    const val MIN_REP_SECONDS = 20
    const val MERGE_GAP_SECONDS = 10
    const val MIN_CANDIDATE_REPS = 3
    const val MAX_STORED_REPS = 60

    /** {tenths of m/s: seconds} over moving time, or null without a speed stream. */
    fun speedHistogram(time: DoubleArray, speed: DoubleArray?): Map<Int, Double>? {
        if (speed == null || time.isEmpty()) return null
        val moving = Resample.oneHz(time, speed).filter { it >= MIN_MOVING_SPEED }
        if (moving.isEmpty()) return null
        val out = HashMap<Int, Double>()
        for (v in moving) out.merge(round(v / SPEED_BUCKET).toInt(), 1.0, Double::plus)
        return out
    }

    /** [start, end) of each stretch of true. */
    private fun runs(mask: BooleanArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < mask.size) {
            if (mask[i]) {
                var j = i
                while (j < mask.size && mask[j]) j++
                out += intArrayOf(i, j)
                i = j
            } else i++
        }
        return out
    }

    /** Centred 5-sample mean, with the window shrunk at the ends (pandas `rolling(5, center=True, min_periods=1)`). */
    private fun smooth(x: DoubleArray): DoubleArray {
        val half = SMOOTHING_SECONDS / 2
        val c = DoubleArray(x.size + 1)
        for (i in x.indices) c[i + 1] = c[i] + x[i]
        return DoubleArray(x.size) { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(x.size - 1, i + half)
            (c[hi + 1] - c[lo]) / (hi - lo + 1)
        }
    }

    /** Candidate interval reps, in seconds of moving time and m/s. Empty unless the run alternates clearly between fast and slow. */
    fun detectReps(time: DoubleArray, speed: DoubleArray?): List<Rep> {
        if (speed == null || time.isEmpty()) return emptyList()
        val s = Resample.oneHz(time, speed)
        if (s.size < MIN_PROFILE_SECONDS) return emptyList()
        val values = smooth(s)
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        val low = TrainingLoad.percentile(sorted, CONTRAST_QUANTILES[0] * 100)
        val high = TrainingLoad.percentile(sorted, CONTRAST_QUANTILES[1] * 100)
        if (median <= 0 || (high - low) / median < MIN_CONTRAST) return emptyList()

        val threshold = (high + low) / 2
        val merged = ArrayList<IntArray>()
        for (r in runs(BooleanArray(values.size) { values[it] >= threshold })) {
            val last = merged.lastOrNull()
            if (last != null && r[0] - last[1] < MERGE_GAP_SECONDS) last[1] = r[1] else merged += intArrayOf(r[0], r[1])
        }
        val reps = merged.filter { it[1] - it[0] >= MIN_REP_SECONDS }
        if (reps.size < MIN_CANDIDATE_REPS) return emptyList()
        return reps.take(MAX_STORED_REPS).mapIndexed { i, (a, b) ->
            val next = reps.getOrNull(i + 1)?.get(0)
            Rep(
                a, b - a, (a until b).sumOf { values[it] } / (b - a),
                next?.let { it - b },
                if (next != null && next > b) (b until next).sumOf { values[it] } / (next - b) else null,
            )
        }
    }
}
