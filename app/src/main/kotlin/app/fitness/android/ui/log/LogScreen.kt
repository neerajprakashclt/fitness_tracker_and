package app.fitness.android.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.Labels
import app.fitness.android.ui.charts.ChartLine
import app.fitness.android.ui.charts.LinesChart
import app.fitness.android.ui.charts.StackedBarChart
import app.fitness.android.ui.components.FilterPill
import app.fitness.android.ui.components.PrimaryButton
import app.fitness.android.ui.components.QuietButton
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.SegmentedControl
import app.fitness.android.ui.components.StatTile
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.Formatter
import app.fitness.core.Goal
import app.fitness.core.GoalProgress
import app.fitness.core.LogActivity
import app.fitness.core.LogMetric
import app.fitness.core.Period
import app.fitness.core.PeriodTotals
import app.fitness.core.TrainingLog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class LogTab(val label: String) { TOTALS("Totals"), CALENDAR("Calendar"), YEARS("Years"), REVIEW("Review"), GOALS("Goals") }

private val weekLabel = DateTimeFormatter.ofPattern("d MMM yy")
private val monthLabel = DateTimeFormatter.ofPattern("MMM yyyy")
private val dayLabel = DateTimeFormatter.ofPattern("EEE d MMM")

/** The training log: totals by week, month or year, a month calendar, year against year, and goals. */
@Composable
fun LogScreen(vm: LogViewModel, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    val rows by vm.rows.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val sports by vm.sports.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LogTab.TOTALS) }
    var sport by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(t.background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (t.layout == LayoutStyle.TERMINAL) "$ log" else "Log", color = t.text,
            fontSize = if (t.layout == LayoutStyle.GROUPED) 32.sp else 26.sp, fontWeight = t.numeralWeight,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        )
        SegmentedControl(LogTab.entries, tab, { it.label }, { tab = it })
        if (tab != LogTab.GOALS && tab != LogTab.REVIEW) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPill("All", sport == null, { sport = null })
            sports.forEach { FilterPill(Labels.sportName(it.sport), sport == it.sport, { sport = it.sport }) }
        }
        val all = rows
        if (all == null) Text("Loading…", color = t.muted, modifier = Modifier.padding(4.dp))
        else if (all.isEmpty()) Text("Nothing here yet. Import some activities first.", color = t.muted, modifier = Modifier.padding(4.dp))
        else when (tab) {
            LogTab.TOTALS -> TotalsView(all, sport, fmt)
            LogTab.CALENDAR -> CalendarView(all, sport, fmt, onOpen)
            LogTab.YEARS -> YearsView(all, sport, fmt)
            LogTab.REVIEW -> ReviewView(all, sports.map { it.sport }, records, fmt, onOpen)
            LogTab.GOALS -> GoalsView(all, goals, sports.map { it.sport }, fmt, vm)
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}

// --- units -----------------------------------------------------------------------------------------------

/** A metric's value in the user's units, and what to call the unit. */
private fun shown(m: LogMetric, v: Double, fmt: Formatter): Double = when (m) {
    LogMetric.DISTANCE -> fmt.toDist(v); LogMetric.DURATION -> v / 3600; LogMetric.ELEVATION -> fmt.toElev(v)
    LogMetric.ACTIVITIES, LogMetric.LOAD -> v
}

private fun unit(m: LogMetric, fmt: Formatter) = when (m) {
    LogMetric.DISTANCE -> fmt.distUnit; LogMetric.DURATION -> "h"; LogMetric.ELEVATION -> fmt.elevUnit; LogMetric.ACTIVITIES -> "activities"; LogMetric.LOAD -> "load"
}

private fun amount(m: LogMetric, v: Double, fmt: Formatter): String = when (m) {
    LogMetric.DURATION -> Formatter.hours(v)
    LogMetric.ACTIVITIES -> "%.0f".format(v)
    LogMetric.LOAD -> "%.0f".format(v)
    LogMetric.DISTANCE -> fmt.distance(v)
    LogMetric.ELEVATION -> fmt.elevation(v)
}

private fun periodName(p: Period, start: LocalDate) = when (p) {
    Period.WEEK -> start.format(weekLabel); Period.MONTH -> start.format(monthLabel); Period.YEAR -> start.year.toString()
}

// --- totals ----------------------------------------------------------------------------------------------

@Composable
private fun TotalsView(rows: List<LogActivity>, sport: String?, fmt: Formatter) {
    val t = Fitness.theme
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var metric by rememberSaveable { mutableStateOf(LogMetric.DISTANCE) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val totals = remember(rows, period, sport) { TrainingLog.totals(rows, period, sport) }
    if (totals.isEmpty()) { Text("No activities for this sport.", color = t.muted, modifier = Modifier.padding(4.dp)); return }

    SegmentedControl(Period.entries, period, { it.label }, { period = it })
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LogMetric.entries.forEach { FilterPill(it.label, it == metric, { metric = it }) }
    }
    val chartN = when (period) { Period.WEEK -> 26; Period.MONTH -> 24; Period.YEAR -> totals.size }
    val recent = totals.takeLast(chartN)
    val current = totals.last()
    val per = recent.map { it.value(metric) }
    ThemedCard {
        Row(Modifier.fillMaxWidth()) {
            StatTile("This ${period.name.lowercase()}", amount(metric, current.value(metric), fmt), Modifier.weight(1f))
            StatTile("Average per ${period.name.lowercase()}", amount(metric, per.average(), fmt), Modifier.weight(1f))
        }
        Box(Modifier.height(12.dp))
        StackedBarChart(
            recent.map { doubleArrayOf(shown(metric, it.value(metric), fmt)) }, listOf(t.accent),
            periodName(period, recent.first().start), periodName(period, recent.last().start),
            { "%.0f ${unit(metric, fmt)}".format(it) },
        )
        if (metric == LogMetric.ELEVATION && recent.sumOf { it.elevationMissing } > 0) {
            Text("Some activities have a GPS track but no elevation, so their climb is not counted.", color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    SectionHeader("By ${period.name.lowercase()}")
    ThemedCard {
        Row(Modifier.fillMaxWidth()) {
            Text(period.label, color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1.4f))
            Text("Acts", color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
            Text("Distance", color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Time", color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        ThemedDivider(Modifier.padding(vertical = 6.dp))
        totals.asReversed().take(if (showAll) totals.size else 16).forEach { r -> TotalsRow(period, r, fmt) }
        if (totals.size > 16) QuietButton(if (showAll) "Show fewer" else "Show all ${totals.size}", { showAll = !showAll })
    }
}

@Composable
private fun TotalsRow(period: Period, r: PeriodTotals, fmt: Formatter) {
    val t = Fitness.theme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(periodName(period, r.start), color = t.text, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
        Text("${r.activities}", color = t.text, fontSize = 13.sp, modifier = Modifier.weight(0.6f))
        Text(if (r.distance > 0) fmt.distance(r.distance) else "–", color = t.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (r.duration > 0) Formatter.hours(r.duration) else "–", color = t.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

// --- calendar --------------------------------------------------------------------------------------------

@Composable
private fun CalendarView(rows: List<LogActivity>, sport: String?, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    val last = rows.maxOf { it.day }
    var month by rememberSaveable { mutableStateOf(last.withDayOfMonth(1).toString()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val first = LocalDate.parse(month)
    val cal = remember(rows, month, sport) { TrainingLog.monthCalendar(rows, first.year, first.monthValue, sport) }
    val byDay = cal.days.associateBy { it.date }
    val today = LocalDate.now()

    ThemedCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("‹", { month = first.minusMonths(1).toString(); selected = null })
            Text(first.format(monthLabel), color = t.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            QuietButton("›", { month = first.plusMonths(1).toString(); selected = null })
        }
        Row(Modifier.fillMaxWidth()) {
            for (d in 0 until 7) Text(
                java.time.DayOfWeek.of(d + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), color = t.muted, fontSize = 11.sp,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        val cells = cal.firstWeekday + cal.daysInMonth
        for (week in 0 until (cells + 6) / 7) Row(Modifier.fillMaxWidth()) {
            for (col in 0 until 7) {
                val n = week * 7 + col - cal.firstWeekday + 1
                if (n < 1 || n > cal.daysInMonth) { Box(Modifier.weight(1f).aspectRatio(0.9f)); continue }
                val date = first.withDayOfMonth(n)
                val day = byDay[date]
                val isSel = selected == date.toString()
                Column(
                    Modifier.weight(1f).aspectRatio(0.9f).padding(1.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(if (isSel) t.accent.copy(alpha = 0.25f) else if (day != null) t.accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                        .border(if (date == today) 1.dp else 0.dp, t.accent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(enabled = day != null) { selected = if (isSel) null else date.toString() }.padding(top = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("$n", color = if (day != null) t.text else t.muted, fontSize = 12.sp)
                    if (day != null) {
                        Text(
                            if (day.totals.distance > 0) "%.1f".format(fmt.toDist(day.totals.distance)) else "${(day.totals.duration / 60).toInt()}m",
                            color = t.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (day.activities.size > 1) Text("×${day.activities.size}", color = t.muted, fontSize = 9.sp)
                    }
                }
            }
        }
        val total = TrainingLog.totals(rows, Period.MONTH, sport, first, first.plusMonths(1).minusDays(1)).firstOrNull()
        Text(
            if (total == null) "No activities this month." else "${total.activities} activities · ${fmt.distance(total.distance)} · ${Formatter.hours(total.duration)}",
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
    selected?.let { d ->
        byDay[LocalDate.parse(d)]?.let { day ->
            SectionHeader(LocalDate.parse(d).format(dayLabel))
            ThemedCard {
                day.activities.forEachIndexed { i, a ->
                    if (i > 0) ThemedDivider()
                    Row(Modifier.fillMaxWidth().clickable { onOpen(a.key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Labels.glyph(a.sport), fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Labels.sportName(a.sport), color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(Formatter.duration(a.duration), color = t.muted, fontSize = 12.sp)
                        }
                        if ((a.distance ?: 0.0) > 0) Text(fmt.distance(a.distance), color = t.text, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

// --- years -----------------------------------------------------------------------------------------------

@Composable
private fun YearsView(rows: List<LogActivity>, sport: String?, fmt: Formatter) {
    val t = Fitness.theme
    var metric by rememberSaveable { mutableStateOf(LogMetric.DISTANCE) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(LogMetric.DISTANCE, LogMetric.DURATION, LogMetric.ELEVATION, LogMetric.LOAD).forEach { FilterPill(it.label, it == metric, { metric = it }) }
    }
    val yoy = remember(rows, metric, sport) { TrainingLog.yearOverYear(rows, metric, LocalDate.now(), sport) }
    val years = yoy.years.keys.sorted().takeLast(8)
    if (years.isEmpty()) { Text("No activities for this sport.", color = t.muted, modifier = Modifier.padding(4.dp)); return }
    val lines = years.mapIndexed { i, y ->
        val v = yoy.years.getValue(y).filterNotNull()
        val fraction = if (years.size == 1) 1f else i / (years.size - 1f)
        val color = androidx.compose.ui.graphics.lerp(t.muted.copy(alpha = 0.55f), t.accent, fraction)
        ChartLine("$y", DoubleArray(v.size) { it.toDouble() }, DoubleArray(v.size) { shown(metric, v[it], fmt) }, color)
    }
    val months = (1..12).map { java.time.Month.of(it).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    ThemedCard {
        LinesChart("Running total over the year (${unit(metric, fmt)})", lines, { months.getOrNull(it.toInt()) ?: "" }, { "%.0f".format(it) }, height = 220)
        Text(
            "Each line is a year, adding up month by month, so this year can be compared with the others at the same point. The last ${years.size} years are drawn; this year is the brightest.",
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    SectionHeader("Year totals")
    ThemedCard {
        years.asReversed().forEach { y ->
            val last = yoy.years.getValue(y).filterNotNull().lastOrNull() ?: 0.0
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("$y", color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(amount(metric, last, fmt), color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// --- goals -----------------------------------------------------------------------------------------------

@Composable
private fun GoalsView(rows: List<LogActivity>, goals: List<Goal>, sports: List<String>, fmt: Formatter, vm: LogViewModel) {
    val t = Fitness.theme
    var adding by remember { mutableStateOf(false) }
    val progress = remember(rows, goals) { TrainingLog.goalProgress(goals, rows, LocalDate.now()) }
    if (goals.isEmpty()) Text(
        "Goals are a target for a week, a month or a year, such as 30 miles a month or 4 runs a week. Progress is worked out from your activities.",
        color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp),
    )
    progress.forEach { GoalCard(it, fmt) { vm.deleteGoal(it.goal.id) } }
    PrimaryButton("Add a goal", { adding = true }, Modifier.fillMaxWidth())
    if (adding) AddGoalDialog(sports, fmt, { adding = false }) { sport, metric, period, target -> vm.addGoal(sport, metric, period, target); adding = false }
}

@Composable
private fun GoalCard(p: GoalProgress, fmt: Formatter, onDelete: () -> Unit) {
    val t = Fitness.theme
    val g = p.goal
    val color = if (p.onTrack) t.accent else t.danger
    ThemedCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${g.metric.label} this ${g.period.name.lowercase()}" + (g.sport?.let { " · ${Labels.sportName(it)}" } ?: ""),
                    color = t.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Text("${p.periodStart.format(weekLabel)} to ${p.periodEnd.format(weekLabel)}", color = t.muted, fontSize = 12.sp)
            }
            QuietButton("Remove", onDelete, danger = true)
        }
        Text("${amount(g.metric, p.current, fmt)} of ${amount(g.metric, g.target, fmt)}", color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(t.divider)) {
            Box(Modifier.fillMaxWidth(p.fraction.toFloat().coerceIn(0f, 1f)).height(10.dp).clip(CircleShape).background(color))
            // A tick where the calendar says we should be.
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(p.expectedFraction.toFloat().coerceIn(0.001f, 0.999f)))
                Box(Modifier.height(10.dp).padding(horizontal = 1.dp).background(t.text).padding(horizontal = 1.dp))
                Box(Modifier.weight((1 - p.expectedFraction).toFloat().coerceIn(0.001f, 0.999f)))
            }
        }
        val gap = p.expectedFraction * g.target - p.current
        Text(
            when {
                p.fraction >= 1 -> "Goal reached."
                p.onTrack -> "On track: ahead of the calendar."
                else -> "Behind the calendar by ${amount(g.metric, gap, fmt)}."
            },
            color = if (p.onTrack) t.muted else t.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
        if (p.elevationMissing > 0) Text("${p.elevationMissing} activities have no elevation data and add nothing to this.", color = t.muted, fontSize = 12.sp)
    }
}

@Composable
private fun AddGoalDialog(sports: List<String>, fmt: Formatter, onDismiss: () -> Unit, onSave: (String?, LogMetric, Period, Double) -> Unit) {
    val t = Fitness.theme
    var metric by remember { mutableStateOf(LogMetric.DISTANCE) }
    var period by remember { mutableStateOf(Period.MONTH) }
    var sport by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val value = text.toDoubleOrNull()
    // The target is typed in the user's units and stored in metres, seconds or a count.
    val stored = value?.let {
        when (metric) {
            LogMetric.DISTANCE -> it * (if (fmt.distUnit == "mi") Formatter.M_PER_MILE else 1000.0)
            LogMetric.DURATION -> it * 3600
            LogMetric.ELEVATION -> it * (if (fmt.elevUnit == "ft") Formatter.M_PER_FOOT else 1.0)
            else -> it
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedControl(listOf(LogMetric.DISTANCE, LogMetric.DURATION, LogMetric.ACTIVITIES, LogMetric.ELEVATION), metric, { it.label }, { metric = it })
                SegmentedControl(Period.entries, period, { it.label }, { period = it })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill("Any sport", sport == null, { sport = null })
                    sports.forEach { FilterPill(Labels.sportName(it), sport == it, { sport = it }) }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text("Target (${unit(metric, fmt)} per ${period.name.lowercase()})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { if (stored != null && stored > 0) QuietButton("Save", { onSave(sport, metric, period, stored) }) },
        dismissButton = { QuietButton("Cancel", onDismiss) },
    )
}
