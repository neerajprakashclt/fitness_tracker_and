package app.fitness.core

import java.time.LocalDate
import kotlin.math.sqrt

/** One day's safety numbers; a null means there is not enough history yet. */
class SafetyDay(
    val date: LocalDate, val load: Double, val acwr: Double?, val ramp: Double?, val monotony: Double?, val strain: Double?,
    val restDays: Int?, val weekLoad: Double?, val chronicDaily: Double?,
) {
    val band: String? get() = LoadSafety.band(acwr)
}

class SafetyWarning(val level: String, val code: String, val message: String)

class SafetyReport(val now: SafetyDay, val warnings: List<SafetyWarning>, val series: List<SafetyDay>)

/**
 * Load safety (PC: `load_safety.py`): how fast training load is rising, and flags when it rises too fast. Rules of thumb from
 * group studies, not a prediction for any one person and not medical advice.
 *  - acute:chronic ratio: mean daily load of the last 7 days over the last 28 (the base is floored so a return from a long break
 *    does not read as a spike). Around 0.8 to 1.3 is the usual sweet spot; above 1.5 is associated with more injuries.
 *  - ramp rate: how much fitness (CTL) rose over the last 7 days; about 5 a week is sustainable, 8 or more a very fast build.
 *  - monotony and strain (Foster): mean over standard deviation of the last 7 daily loads, and weekly load times monotony.
 */
object LoadSafety {
    const val ACUTE_DAYS = 7
    const val CHRONIC_DAYS = 28
    const val RAMP_DAYS = 7
    const val ACWR_SWEET_LOW = 0.8
    const val ACWR_CAUTION = 1.3
    const val ACWR_HIGH = 1.5
    const val RAMP_SUSTAINABLE = 5.0
    const val RAMP_VERY_FAST = 8.0
    const val MONOTONY_HIGH = 2.0
    /** A near-zero base makes a "low" ratio meaningless. */
    const val MIN_CHRONIC_FOR_LOW_FLAG = 15.0
    /** Daily load; stops the ratio exploding when coming back from a break. */
    const val CHRONIC_FLOOR = 10.0

    private fun mean(x: List<Double>) = x.sum() / x.size
    private fun pstdev(x: List<Double>): Double { val m = mean(x); return sqrt(x.sumOf { (it - m) * (it - m) } / x.size) }

    /** [days] one per calendar day, oldest first (the daily load series). */
    fun metrics(days: List<DayLoad>): List<SafetyDay> {
        val loads = days.map { it.load }
        return days.mapIndexed { i, d ->
            val week = if (i >= ACUTE_DAYS - 1) loads.subList(i - ACUTE_DAYS + 1, i + 1) else null
            val month = if (i >= CHRONIC_DAYS - 1) loads.subList(i - CHRONIC_DAYS + 1, i + 1) else null
            var monotony: Double? = null
            var strain: Double? = null
            var restDays: Int? = null
            var weekLoad: Double? = null
            if (week != null) {
                val sd = pstdev(week)
                weekLoad = week.sum()
                restDays = week.count { it == 0.0 }
                if (sd > 0) { monotony = mean(week) / sd; strain = week.sum() * monotony }
            }
            var chronic: Double? = null
            var acwr: Double? = null
            if (month != null) {
                chronic = mean(month)
                if (chronic > 0) acwr = mean(week!!) / maxOf(chronic, CHRONIC_FLOOR)
            }
            val ramp = if (i >= RAMP_DAYS) d.ctl - days[i - RAMP_DAYS].ctl else null
            SafetyDay(d.date, d.load, acwr, ramp, monotony, strain, restDays, weekLoad, chronic)
        }
    }

    fun band(acwr: Double?): String? = when {
        acwr == null -> null
        acwr > ACWR_HIGH -> "high"
        acwr > ACWR_CAUTION -> "caution"
        acwr >= ACWR_SWEET_LOW -> "optimal"
        else -> "low"
    }

    fun warnings(now: SafetyDay): List<SafetyWarning> = buildList {
        val acwr = now.acwr
        val ramp = now.ramp
        val mono = now.monotony
        if (acwr != null) {
            if (acwr > ACWR_HIGH) add(SafetyWarning("high", "acwr_spike", "Your last 7 days are %.2fx your 4-week average. Sudden jumps like this are linked with more injuries; consider easier days.".format(acwr)))
            else if (acwr > ACWR_CAUTION) add(SafetyWarning("caution", "acwr_rising", "Your last 7 days are %.2fx your 4-week average: a quick build. Keep an eye on how you feel.".format(acwr)))
            else if (acwr < ACWR_SWEET_LOW && (now.chronicDaily ?: 0.0) >= MIN_CHRONIC_FOR_LOW_FLAG)
                add(SafetyWarning("info", "acwr_low", "Your last 7 days are only %.2fx your 4-week average: a drop in training (fine if it's planned rest).".format(acwr)))
        }
        if (ramp != null) {
            if (ramp > RAMP_VERY_FAST) add(SafetyWarning("high", "ramp_fast", "Fitness rose %.1f points this week, a very fast build (about %.0f a week is sustainable for most people).".format(ramp, RAMP_SUSTAINABLE)))
            else if (ramp > RAMP_SUSTAINABLE) add(SafetyWarning("caution", "ramp_aggressive", "Fitness rose %.1f points this week, an aggressive build.".format(ramp)))
        }
        if (mono != null && mono > MONOTONY_HIGH) add(SafetyWarning("caution", "monotony", "Your training has been very uniform this week (monotony %.1f). Mixing easy and hard days spreads the stress.".format(mono)))
        if (now.restDays == 0 && (now.weekLoad ?: 0.0) > 0) add(SafetyWarning("info", "no_rest", "No rest day in the last 7 days."))
    }

    /** Null until there are [CHRONIC_DAYS] days of history. */
    fun analysis(days: List<DayLoad>, shown: Int = 180): SafetyReport? {
        if (days.size < CHRONIC_DAYS) return null
        val series = metrics(days)
        return SafetyReport(series.last(), warnings(series.last()), series.takeLast(shown))
    }
}
