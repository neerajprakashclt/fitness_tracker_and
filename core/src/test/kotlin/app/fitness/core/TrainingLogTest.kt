package app.fitness.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainingLogTest {
    private fun run(day: String, km: Double, sport: String = "running", minutes: Double = 30.0, elevation: Double? = 10.0) =
        LogActivity("$sport$day", sport, LocalDate.parse(day), minutes * 60, km * 1000, elevation, km * 5, false)

    // 2026-10-14 is a Wednesday; its week starts on Monday the 12th.
    private val rows = listOf(run("2026-10-05", 5.0), run("2026-10-12", 8.0), run("2026-10-14", 4.0), run("2026-10-14", 20.0, "cycling"))

    @Test
    fun periodsStartOnMondayFirstOfMonthAndFirstOfYear() {
        val d = LocalDate.parse("2026-10-14")
        assertEquals(LocalDate.parse("2026-10-12"), TrainingLog.periodStart(d, Period.WEEK))
        assertEquals(LocalDate.parse("2026-10-01"), TrainingLog.periodStart(d, Period.MONTH))
        assertEquals(LocalDate.parse("2026-01-01"), TrainingLog.periodStart(d, Period.YEAR))
        assertEquals(LocalDate.parse("2027-01-01"), TrainingLog.nextPeriod(LocalDate.parse("2026-12-01"), Period.MONTH))
    }

    @Test
    fun emptyWeeksBetweenAreFilledWithZeros() {
        val weeks = TrainingLog.totals(rows + run("2026-09-14", 3.0), Period.WEEK, "running")
        assertEquals(listOf("2026-09-14", "2026-09-21", "2026-09-28", "2026-10-05", "2026-10-12"), weeks.map { it.start.toString() })
        assertEquals(listOf(1, 0, 0, 1, 2), weeks.map { it.activities })
        assertEquals(12000.0, weeks.last().distance)
    }

    @Test
    fun calendarGivesTheMondayFirstOffsetAndGroupsByDay() {
        val cal = TrainingLog.monthCalendar(rows, 2026, 10)
        assertEquals(3, cal.firstWeekday)                       // 1 October 2026 is a Thursday
        assertEquals(31, cal.daysInMonth)
        assertEquals(listOf(1, 1, 2), cal.days.map { it.activities.size })
        assertEquals(24000.0, cal.days.last().totals.distance)
    }

    @Test
    fun yearOverYearIsCumulativeAndStopsAtTheCurrentMonth() {
        val y = TrainingLog.yearOverYear(rows, LogMetric.DISTANCE, LocalDate.parse("2026-10-14"), "running").years.getValue(2026)
        assertEquals(17000.0, y[9])
        assertEquals(null, y[10])
        assertEquals(0.0, y[0])
    }

    @Test
    fun goalProgressComparesWithTheCalendar() {
        val today = LocalDate.parse("2026-10-14")                // day 3 of a 7-day week
        val goals = listOf(
            Goal("a", "running", LogMetric.DISTANCE, Period.WEEK, 20000.0),
            Goal("b", null, LogMetric.ACTIVITIES, Period.MONTH, 10.0),
            Goal("c", "running", LogMetric.DISTANCE, Period.WEEK, 10000.0),
        )
        val p = TrainingLog.goalProgress(goals, rows, today)
        assertEquals(12000.0, p[0].current)
        assertEquals(3.0 / 7, p[0].expectedFraction, 1e-12)
        assertEquals(0.6, p[0].fraction, 1e-12)
        assertTrue(p[0].onTrack)
        assertEquals(4.0, p[1].current)                          // all sports: the 5th, the 12th and two on the 14th
        assertEquals(LocalDate.parse("2026-10-18"), p[0].periodEnd)
        assertTrue(p[2].onTrack)
        assertEquals(1.2, p[2].fraction, 1e-12)
    }

    @Test
    fun polarizationLabels() {
        assertEquals("polarized", WeeklyZoneReport.polarization(doubleArrayOf(70.0, 10.0, 5.0, 10.0, 5.0))!!.label)
        assertEquals("pyramidal", WeeklyZoneReport.polarization(doubleArrayOf(70.0, 10.0, 15.0, 4.0, 1.0))!!.label)
        assertEquals("moderate", WeeklyZoneReport.polarization(doubleArrayOf(30.0, 30.0, 20.0, 15.0, 5.0))!!.label)
        assertEquals("intensity-heavy", WeeklyZoneReport.polarization(doubleArrayOf(10.0, 10.0, 30.0, 30.0, 20.0))!!.label)
        assertEquals(null, WeeklyZoneReport.polarization(DoubleArray(5)))
    }
}
