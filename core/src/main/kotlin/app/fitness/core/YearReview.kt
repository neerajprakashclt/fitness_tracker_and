package app.fitness.core

import java.time.LocalDate
import java.time.Month

class Consistency(
    val currentStreakWeeks: Int, val longestStreakWeeks: Int, val longestStreakFrom: LocalDate?, val longestStreakTo: LocalDate?,
    val longestGapDays: Int, val longestGapFrom: LocalDate?, val longestGapTo: LocalDate?, val daysSinceLast: Long,
    val perWeekRecent: Double, val activeWeeksLast52: Int,
)

class YearTotals(val runs: Int, val distance: Double, val duration: Double, val elevation: Double, val elevationMissing: Int) {
    val avgSpeed: Double? get() = if (duration != 0.0) distance / duration else null
}

class YearHighlight(val key: String, val date: LocalDate, val distance: Double?)

class YearReview(
    val year: Int, val totals: YearTotals, val previous: YearTotals?,
    /** Relative change from the year before, for distance, time and runs; absent when there was no year before. */
    val change: Map<String, Double>,
    val activeWeeks: Int, val longestRun: YearHighlight, val biggestWeekStart: LocalDate, val biggestWeekDistance: Double,
    val biggestMonth: Month, val biggestMonthDistance: Double, val favouriteWeekday: java.time.DayOfWeek,
    val personalBestsSet: List<Pair<String, RecordSeries.Record>>,
)

/** The consistency and year-in-review parts of the PC's `volume.py`, over one sport's activities. */
object YearInReview {
    const val RECENT_WEEKS = 12

    /** [acts] of one sport in start order. */
    fun consistency(acts: List<LogActivity>, today: LocalDate): Consistency? {
        if (acts.isEmpty()) return null
        val weeks = acts.map { Volume.weekStart(it.day) }.toSortedSet().toList()
        val wk = weeks.toSet()
        var best = 0
        var bestStart: LocalDate? = null
        var run = 0
        var runStart: LocalDate? = null
        var prev: LocalDate? = null
        for (w in weeks) {
            if (prev != null && prev.plusWeeks(1) == w) run++ else { run = 1; runStart = w }
            if (run > best) { best = run; bestStart = runStart }
            prev = w
        }
        // The current streak continues if this week or last week has activity (this week may be young).
        val thisWeek = Volume.weekStart(today)
        var cursor = if (thisWeek in wk) thisWeek else thisWeek.minusWeeks(1)
        var current = 0
        while (cursor in wk) { current++; cursor = cursor.minusWeeks(1) }

        val gaps = acts.zipWithNext { a, b -> (b.day.toEpochDay() - a.day.toEpochDay()).toInt() }
        val gapI = gaps.indices.maxByOrNull { gaps[it] }
        val recentFrom = thisWeek.minusWeeks(RECENT_WEEKS - 1L)
        val last52 = thisWeek.minusWeeks(51)
        return Consistency(
            current, best, bestStart, bestStart?.plusWeeks(best - 1L),
            gapI?.let { gaps[it] } ?: 0, gapI?.let { acts[it].day }, gapI?.let { acts[it + 1].day },
            today.toEpochDay() - acts.last().day.toEpochDay(),
            acts.count { !it.day.isBefore(recentFrom) }.toDouble() / RECENT_WEEKS, wk.count { !it.isBefore(last52) },
        )
    }

    fun years(acts: List<LogActivity>): List<Int> = acts.map { it.day.year }.toSortedSet().toList().reversed()

    private fun totals(acts: List<LogActivity>) = YearTotals(
        acts.size, acts.sumOf { it.distance ?: 0.0 }, acts.sumOf { it.duration ?: 0.0 }, acts.sumOf { it.elevationGain ?: 0.0 }, acts.count { it.elevationMissing },
    )

    /** [acts] of one sport in start order; [records] that sport's personal-best timeline. Null when the year has no activity. */
    fun review(acts: List<LogActivity>, year: Int, records: List<RecordSeries>): YearReview? {
        val mine = acts.filter { it.day.year == year }
        if (mine.isEmpty()) return null
        val t = totals(mine)
        val before = acts.filter { it.day.year == year - 1 }
        val prev = if (before.isEmpty()) null else totals(before)

        val byWeek = LinkedHashMap<LocalDate, Double>()
        val byMonth = LinkedHashMap<Int, Double>()
        val byWeekday = LinkedHashMap<Int, Int>()
        for (a in mine) {
            byWeek.merge(Volume.weekStart(a.day), a.distance ?: 0.0, Double::plus)
            byMonth.merge(a.day.monthValue, a.distance ?: 0.0, Double::plus)
            byWeekday.merge(a.day.dayOfWeek.value - 1, 1, Int::plus)
        }
        val longest = mine.maxByOrNull { it.distance ?: 0.0 }!!          // the first of equals, as Python's max
        val bigWeek = byWeek.entries.maxByOrNull { it.value }!!
        val bigMonth = byMonth.entries.maxByOrNull { it.value }!!
        val change = LinkedHashMap<String, Double>()
        if (prev != null) {
            if (prev.distance != 0.0) change["distance"] = (t.distance - prev.distance) / prev.distance
            if (prev.duration != 0.0) change["duration"] = (t.duration - prev.duration) / prev.duration
            if (prev.runs != 0) change["runs"] = (t.runs - prev.runs).toDouble() / prev.runs
        }
        val pbs = records.flatMap { s -> s.records.filter { it.date.year == year }.map { s.name to it } }
        return YearReview(
            year, t, prev, change, byWeek.size, YearHighlight(longest.key, longest.day, longest.distance), bigWeek.key, bigWeek.value,
            Month.of(bigMonth.key), bigMonth.value, java.time.DayOfWeek.of(byWeekday.entries.maxByOrNull { it.value }!!.key + 1), pbs,
        )
    }
}
