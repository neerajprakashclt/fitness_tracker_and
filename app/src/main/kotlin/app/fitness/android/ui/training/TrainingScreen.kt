package app.fitness.android.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.Labels
import app.fitness.android.ui.charts.ChartLine
import app.fitness.android.ui.charts.LinesChart
import app.fitness.android.ui.components.FilterPill
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.SegmentedControl
import app.fitness.android.ui.components.StatTile
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.CurvePoint
import app.fitness.core.Curves
import app.fitness.core.DayLoad
import app.fitness.core.Formatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private enum class Tab(val label: String) { FITNESS("Fitness"), CURVES("Curves & records") }

@Composable
fun TrainingScreen(vm: TrainingViewModel, fmt: Formatter, onPoster: () -> Unit, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    var tab by rememberSaveable { mutableStateOf(Tab.FITNESS) }
    Column(
        Modifier.fillMaxSize().background(t.background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (t.layout == LayoutStyle.TERMINAL) "$ training" else "Training", color = t.text,
            fontSize = if (t.layout == LayoutStyle.GROUPED) 32.sp else 26.sp, fontWeight = t.numeralWeight,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        )
        SegmentedControl(Tab.entries, tab, { it.label }, { tab = it })
        ThemedCard(onClick = onPoster) {
            Text("Your running life, as a poster", color = t.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Every run you have done, drawn as rings, a spiral, a mountain range and more. Save it as an image.", color = t.muted, fontSize = 12.sp)
        }
        when (tab) {
            Tab.FITNESS -> FitnessTab(vm)
            Tab.CURVES -> CurvesTab(vm, fmt, onOpen)
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}

private val axisDate = DateTimeFormatter.ofPattern("MMM yy")
private val shortDate = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun FitnessTab(vm: TrainingViewModel) {
    val t = Fitness.theme
    val daily by vm.daily.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    if (daily.isEmpty()) {
        Text("No training load yet. It is worked out from your activities once they are imported (a minute or two for a long history).",
            color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        return
    }
    val today = LocalDate.now()
    val shown = remember(daily, range) { thin(daily.filter { range.from(today)?.let { f -> !it.date.isBefore(f) } ?: true }) }
    val now = daily.last()

    ThemedCard {
        Row(Modifier.fillMaxWidth()) {
            StatTile("Fitness (CTL)", "%.0f".format(now.ctl), Modifier.weight(1f))
            StatTile("Fatigue (ATL)", "%.0f".format(now.atl), Modifier.weight(1f))
            StatTile("Form (TSB)", "%+.0f".format(now.tsb), Modifier.weight(1f))
        }
        Text(formText(now.tsb), color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in LoadRange.entries) FilterPill(r.label, r == range, { vm.setRange(r) })
    }
    val xs = DoubleArray(shown.size) { shown[it].date.toEpochDay().toDouble() }
    val dateLabel: (Double) -> String = { LocalDate.ofEpochDay(it.toLong()).format(axisDate) }
    ThemedCard {
        LinesChart(
            "Fitness and fatigue", listOf(
                ChartLine("Fitness", xs, DoubleArray(shown.size) { shown[it].ctl }, t.chart.pace),
                ChartLine("Fatigue", xs, DoubleArray(shown.size) { shown[it].atl }, t.chart.heartRate),
            ), dateLabel, { "%.0f".format(it) },
        )
        ThemedDivider(Modifier.padding(vertical = 4.dp))
        LinesChart("Form", listOf(ChartLine("Form", xs, DoubleArray(shown.size) { shown[it].tsb }, t.chart.elevation, filled = true)), dateLabel, { "%.0f".format(it) }, height = 140)
    }
    Text(
        "Load scores are on one scale (100 is an hour at threshold). Fitness and fatigue are 42-day and 7-day averages of it; form is yesterday's fitness minus fatigue.",
        color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** At most ~400 days drawn, evenly spaced and always including the latest, so the x spacing stays uniform. */
private fun thin(days: List<DayLoad>, max: Int = 400): List<DayLoad> {
    if (days.size <= max) return days
    val step = (days.size + max - 1) / max
    return (days.indices.reversed() step step).toList().reversed().map { days[it] }
}

private fun formText(tsb: Double) = when {
    tsb > 25 -> "Very fresh: well rested, possibly losing fitness."
    tsb > 5 -> "Fresh: ready for a hard effort."
    tsb > -10 -> "Neutral."
    tsb > -30 -> "Tired: productive training load."
    else -> "Very tired: risk of overreaching."
}

@Composable
private fun CurvesTab(vm: TrainingViewModel, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    val sports by vm.sports.collectAsStateWithLifecycle()
    val set by vm.curves.collectAsStateWithLifecycle()
    if (sports.isEmpty() || set == null) {
        Text("No best efforts yet. They come from activities with speed, distance or power data.", color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        return
    }
    val current = set!!
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in sports) FilterPill(Labels.sportName(s), s == current.sport, { vm.select(s) })
    }
    val foot = current.sport in Formatter.PACE_SPORTS
    val metrics = buildList {
        if (current.hasSpeed) add(if (foot) "Pace" else "Speed")
        if (current.hasPower) add("Power")
    }
    var picked by rememberSaveable(current.sport) { mutableStateOf(metrics.firstOrNull()) }
    val metric = picked?.takeIf { it in metrics } ?: metrics.firstOrNull()
    if (metrics.size > 1 && metric != null) SegmentedControl(metrics, metric, { it }, { picked = it })

    if (metric != null) {
        val power = metric == "Power"
        val points = current.curves.getValue(if (power) Curves.Metric.POWER else Curves.Metric.SPEED)
        val xs = DoubleArray(points.size) { it.toDouble() }
        val ys = DoubleArray(points.size) {
            val v = points[it].value
            when { power -> v; foot -> -fmt.toPaceMin(v); else -> fmt.toSpeed(v) }
        }
        ThemedCard {
            LinesChart(
                when { power -> "Best average power (W)"; foot -> "Best pace (min/${fmt.distUnit})"; else -> "Best speed (${fmt.speedUnit})" },
                listOf(ChartLine(metric, xs, ys, if (power) t.chart.power else t.chart.pace, filled = power)),
                { spanLabel(points.getOrNull(it.toInt())?.span) },
                { if (foot && !power) paceLabel(it) else "%.0f".format(it) },
            )
            Text("Best effort over each duration, across all ${Labels.sportName(current.sport).lowercase()} activities.", color = t.muted, fontSize = 12.sp)
        }
    }

    val records = current.curves.getValue(Curves.Metric.DISTANCE_TIME)
    if (records.isNotEmpty()) {
        SectionHeader("Best efforts")
        ThemedCard {
            Row(Modifier.fillMaxWidth()) {
                Text("Distance", color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1.3f))
                Text("Time", color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(fmt.paceOrSpeedLabel(current.sport), color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1.1f))
                Text("Date", color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(1.3f))
            }
            ThemedDivider(Modifier.padding(vertical = 6.dp))
            records.forEach { p -> RecordRow(p, current.sport, fmt, onOpen) }
        }
    }
}

@Composable
private fun RecordRow(p: CurvePoint, sport: String, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    Row(Modifier.fillMaxWidth().clickable { onOpen(p.key) }.padding(vertical = 6.dp)) {
        Text(distanceName(p.span), color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.3f))
        Text(Formatter.duration(p.value), color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(fmt.paceOrSpeed(sport, p.span / p.value), color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1.1f))
        Text(p.date.format(shortDate), color = t.muted, fontSize = 13.sp, modifier = Modifier.weight(1.3f))
    }
}

private fun distanceName(m: Double): String = when (m) {
    400.0 -> "400 m"; 1000.0 -> "1 km"; 1609.34 -> "1 mile"; 5000.0 -> "5K"; 10000.0 -> "10K"; 16093.4 -> "10 miles"
    21097.5 -> "Half marathon"; 42195.0 -> "Marathon"; 20000.0 -> "20 km"; 40000.0 -> "40 km"; 50000.0 -> "50 km"
    100000.0 -> "100 km"; 160934.0 -> "100 miles"
    else -> "%.0f m".format(m)
}

private fun spanLabel(seconds: Double?): String {
    val s = seconds?.toInt() ?: return ""
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> if (s % 60 == 0) "${s / 60}m" else "${s / 60}:%02d".format(s % 60)
        s % 3600 == 0 -> "${s / 3600}h"
        else -> "%.1fh".format(s / 3600.0)
    }
}

private fun paceLabel(negMinutes: Double): String {
    val total = (abs(negMinutes) * 60).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
