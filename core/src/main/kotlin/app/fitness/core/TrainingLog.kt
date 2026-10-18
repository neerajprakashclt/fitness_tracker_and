package app.fitness.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year") }

enum class LogMetric(val label: String) {
    DISTANCE("Distance"), DURATION("Time"), ACTIVITIES("Activities"), ELEVATION("Elevation"), LOAD("Load");

    /** The metrics a goal can be set on. */
    val goalable get() = this != LOAD
}

/** One activity as the log sees it. [day] is the local date. */
class LogActivity(
    val key: String, val sport: String, val day: LocalDate, val duration: Double?, val distance: Double?,
    val elevationGain: Double?, val load: Double?,
    /** It has a GPS track but no elevation, so its climb is unknown rather than zero. */
    val elevationMissing: Boolean,
)

class PeriodTotals(
    val start: LocalDate, val activities: Int, val distance: Double, val duration: Double, val elevation: Double, val load: Double,
    /** How many activities add nothing to [elevation] for want of data. */
    val elevationMissing: Int,
) {
    fun value(m: LogMetric): Double = when (m) {
        LogMetric.DISTANCE -> distance; LogMetric.DURATION -> duration; LogMetric.ACTIVITIES -> activities.toDouble()
        LogMetric.ELEVATION -> elevation; LogMetric.LOAD -> load
    }
}

class CalendarDay(val date: LocalDate, val totals: PeriodTotals, val activities: List<LogActivity>)

class MonthCalendar(val year: Int, val month: Int, val firstWeekday: Int, val daysInMonth: Int, val days: List<CalendarDay>)

/** Cumulative metric by month for each year. A null month has not happened yet. */
class YearOverYear(val metric: LogMetric, val years: Map<Int, List<Double?>>)

class Goal(val id: String, val sport: String?, val metric: LogMetric, val period: Period, val target: Double)

class GoalProgress(
    val goal: Goal, val current: Double, val fraction: Double,
    /** How far through the period we are; on track means at least as far along as the calendar. */
    val expectedFraction: Double, val periodStart: LocalDate, val periodEnd: LocalDate, val elevationMissing: Int,
) {
    val onTrack get() = fraction >= expectedFraction
}

/** Port of the PC app's `analytics/log.py` and `dates.py`: period totals, a month calendar, year over year, and goals. */
object TrainingLog {
    fun periodStart(d: LocalDate, p: Period): LocalDate = when (p) {
        Period.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> d.withDayOfMonth(1)
        Period.YEAR -> d.withDayOfYear(1)
    }

    /** First day of the period after the one starting at [start]. */
    fun nextPeriod(start: LocalDate, p: Period): LocalDate = when (p) {
        Period.WEEK -> start.plusDays(7)
        Period.MONTH -> start.plusMonths(1)
        Period.YEAR -> start.plusYears(1)
    }

    private class Acc {
        var n = 0; var distance = 0.0; var duration = 0.0; var elevation = 0.0; var load = 0.0; var missing = 0
        fun add(r: LogActivity) {
            n++; distance += r.distance ?: 0.0; duration += r.duration ?: 0.0; elevation += r.elevationGain ?: 0.0; load += r.load ?: 0.0
            if (r.elevationMissing) missing++
        }
        fun totals(start: LocalDate) = PeriodTotals(start, n, distance, duration, elevation, load, missing)
    }

    /** One row per week/month/year, oldest first, with empty periods filled in as zeros. */
    fun totals(rows: List<LogActivity>, period: Period, sport: String? = null, from: LocalDate? = null, to: LocalDate? = null): List<PeriodTotals> {
        val buckets = HashMap<LocalDate, Acc>()
        for (r in rows) {
            if ((sport != null && r.sport != sport) || (from != null && r.day.isBefore(from)) || (to != null && r.day.isAfter(to))) continue
            buckets.getOrPut(periodStart(r.day, period)) { Acc() }.add(r)
        }
        if (buckets.isEmpty()) return emptyList()
        val out = ArrayList<PeriodTotals>()
        var start = buckets.keys.min()
        val last = buckets.keys.max()
        while (!start.isAfter(last)) {
            out += (buckets[start] ?: Acc()).totals(start)
            start = nextPeriod(start, period)
        }
        return out
    }

    fun monthCalendar(rows: List<LogActivity>, year: Int, month: Int, sport: String? = null): MonthCalendar {
        val byDay = rows.filter { it.day.year == year && it.day.monthValue == month && (sport == null || it.sport == sport) }.groupBy { it.day }
        val first = LocalDate.of(year, month, 1)
        return MonthCalendar(
            year, month, first.dayOfWeek.value - 1, first.lengthOfMonth(),
            byDay.toSortedMap().map { (d, list) -> CalendarDay(d, Acc().also { a -> list.forEach(a::add) }.totals(d), list) },
        )
    }

    fun yearOverYear(rows: List<LogActivity>, metric: LogMetric, today: LocalDate, sport: String? = null): YearOverYear {
        val perMonth = sortedMapOf<Int, DoubleArray>()
        for (r in rows) {
            if (sport != null && r.sport != sport) continue
            val one = Acc().also { it.add(r) }.totals(r.day)
            perMonth.getOrPut(r.day.year) { DoubleArray(12) }[r.day.monthValue - 1] += one.value(metric)
        }
        return YearOverYear(metric, perMonth.mapValues { (year, monthly) ->
            var running = 0.0
            monthly.mapIndexed { i, v -> running += v; if (year == today.year && i + 1 > today.monthValue) null else running }
        })
    }

    /** Each goal with progress in the current week, month or year. */
    fun goalProgress(goals: List<Goal>, rows: List<LogActivity>, today: LocalDate): List<GoalProgress> = goals.map { g ->
        val start = periodStart(today, g.period)
        val end = nextPeriod(start, g.period)
        val acc = Acc()
        rows.filter { !it.day.isBefore(start) && it.day.isBefore(end) && (g.sport == null || it.sport == g.sport) }.forEach(acc::add)
        val current = acc.totals(start).value(g.metric)
        val elapsed = today.toEpochDay() - start.toEpochDay() + 1
        GoalProgress(
            g, current, current / g.target, elapsed.toDouble() / (end.toEpochDay() - start.toEpochDay()), start, end.minusDays(1),
            if (g.metric == LogMetric.ELEVATION) acc.missing else 0,
        )
    }
}

/** Weekly heart-rate zones and polarization (PC: `zones.py`). */
class ZoneWeek(val weekStart: LocalDate, val seconds: DoubleArray) {
    val total get() = seconds.sum()
}

class Polarization(val easy: Double, val moderate: Double, val hard: Double, val label: String)

class WeeklyZones(val maxHr: Double, val weeks: List<ZoneWeek>, val totals: DoubleArray, val polarization: Polarization?)

object WeeklyZoneReport {
    /** Easy is Z1 and Z2, moderate Z3, hard Z4 and Z5. A split with this much easy time is "polarized" or "pyramidal". */
    const val POLARIZED_MIN_EASY = 0.75

    fun polarization(seconds: DoubleArray): Polarization? {
        val total = seconds.sum()
        if (total <= 0) return null
        val easy = (seconds[0] + seconds[1]) / total
        val moderate = seconds[2] / total
        val hard = (seconds[3] + seconds[4]) / total
        val label = when {
            easy >= POLARIZED_MIN_EASY && hard > moderate -> "polarized"
            easy >= POLARIZED_MIN_EASY -> "pyramidal"
            easy >= 0.5 -> "moderate"
            else -> "intensity-heavy"
        }
        return Polarization(easy, moderate, hard, label)
    }

    /** [rows]: each activity's sport, local day and heart-rate histogram. Weeks with none in between are zeros, so there are no gaps. */
    fun weekly(rows: List<Triple<String, LocalDate, Map<Int, Double>>>, maxHr: Double, sport: String? = null, from: LocalDate? = null, to: LocalDate? = null): WeeklyZones? {
        val weeks = HashMap<LocalDate, DoubleArray>()
        for ((s, day, hist) in rows) {
            if ((sport != null && s != sport) || (from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) continue
            val acc = weeks.getOrPut(Volume.weekStart(day)) { DoubleArray(Zones.FLOORS.size) }
            Zones.timeInZones(hist, maxHr).forEachIndexed { i, v -> acc[i] += v }
        }
        if (weeks.isEmpty()) return null
        val out = ArrayList<ZoneWeek>()
        var w = weeks.keys.min()
        val last = weeks.keys.max()
        while (!w.isAfter(last)) {
            out += ZoneWeek(w, weeks[w] ?: DoubleArray(Zones.FLOORS.size))
            w = w.plusWeeks(1)
        }
        val totals = DoubleArray(Zones.FLOORS.size) { i -> out.sumOf { it.seconds[i] } }
        return WeeklyZones(maxHr, out, totals, polarization(totals))
    }
}
