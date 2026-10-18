package app.fitness.android.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.Labels
import app.fitness.android.ui.RunTypeStyle
import app.fitness.android.ui.charts.ChartLine
import app.fitness.android.ui.charts.LinesChart
import app.fitness.android.ui.components.FilterPill
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.StatTile
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.CsZone
import app.fitness.core.Formatter
import app.fitness.core.RunType
import app.fitness.core.WeekMix
import app.fitness.data.CriticalSpeedScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val monthLabel = DateTimeFormatter.ofPattern("MMM yy")
private val dayLabel = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Critical speed and pace zones, race predictions, the run-type mix and efficiency: what the data says about running. */
@Composable
fun RunningTab(vm: TrainingViewModel, fmt: Formatter) {
    val t = Fitness.theme
    val report by vm.running.collectAsStateWithLifecycle()
    val r = report
    if (r == null) {
        Text("Working out your running profile…", color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        return
    }
    CriticalSpeedCard(r.criticalSpeed, fmt)
    r.criticalSpeed.race?.let { RacePredictionsCard(it, fmt) }
    TrendCard(r.criticalSpeed, fmt)

    SectionHeader("Run types")
    MixCard(r.mix.weeks, r.mix.counts, r.mix.km, fmt)

    SectionHeader("Efficiency")
    EfficiencyCard(vm, r.efficiency, fmt)
}

@Composable
private fun CriticalSpeedCard(cs: CriticalSpeedScreen, fmt: Formatter) {
    val t = Fitness.theme
    SectionHeader("Critical speed")
    val fit = cs.fit
    if (fit == null) {
        ThemedCard {
            Text(
                "Not enough hard efforts yet. A fit needs your best efforts at four different lengths between 3 and 20 minutes within the last 90 days (180 if that is too few).",
                color = t.muted, fontSize = 13.sp,
            )
        }
        return
    }
    ThemedCard {
        Row(Modifier.fillMaxWidth()) {
            StatTile("Critical pace", fmt.pace(fit.cs), Modifier.weight(1f))
            StatTile("Reserve (D′)", "%.0f m".format(fit.dPrime), Modifier.weight(1f))
        }
        Text(
            "From your best efforts of 3 to 20 minutes over the last ${fit.windowDays} days, as of ${fit.asOf.format(dayLabel)} (${fit.points} lengths, fit quality ${"%.3f".format(fit.r2)}). " +
                "It is what your recent efforts support: a race or a hard test would likely show more.",
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        when (fit.note) {
            "no_reserve" -> Text("Your short efforts are no faster than the line, so no sprint reserve shows up; the fit goes through zero.", color = t.muted, fontSize = 12.sp)
            "large_reserve" -> Text("The reserve is unusually large, so treat this fit with care.", color = t.muted, fontSize = 12.sp)
        }
        ThemedDivider(Modifier.padding(vertical = 10.dp))
        Text("Pace zones", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        cs.zones.forEach { z ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("${z.zone}  ${z.name}", color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(zoneRange(z, fmt), color = t.muted, fontSize = 13.sp)
            }
        }
        if (cs.predicted.isNotEmpty()) {
            ThemedDivider(Modifier.padding(vertical = 10.dp))
            Text("From critical speed", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            cs.predicted.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(p.name, color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${Formatter.duration(p.seconds)}  ·  ${fmt.pace(p.speed)}", color = t.text, fontSize = 14.sp)
                }
            }
            Text("Nothing longer than a 5K: the model is too optimistic past about 40 minutes.", color = t.muted, fontSize = 12.sp)
        }
    }
}

/** Pace for a zone: the zones are ranges of speed, so a lower speed bound is the slower end. */
private fun zoneRange(z: CsZone, fmt: Formatter): String = when {
    z.low <= 0.0 -> "slower than ${fmt.paceBare(z.high)} /${fmt.distUnit}"
    z.high == null -> "faster than ${fmt.paceBare(z.low)} /${fmt.distUnit}"
    else -> "${fmt.paceBare(z.low)} – ${fmt.paceBare(z.high)} /${fmt.distUnit}"
}

@Composable
private fun RacePredictionsCard(p: app.fitness.core.Predictions, fmt: Formatter) {
    val t = Fitness.theme
    SectionHeader("Race predictions")
    ThemedCard {
        p.predictions.forEach { r ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(r.name, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(Formatter.duration(r.seconds), color = t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("   ${fmt.pace(r.speed)}", color = t.muted, fontSize = 13.sp)
                }
                Text(
                    if (r.exact) "your best, ${r.from.date.format(dayLabel)}" else "from your ${shortDistance(r.from.span)} of ${Formatter.duration(r.from.value)} on ${r.from.date.format(dayLabel)}",
                    color = t.muted, fontSize = 12.sp,
                )
            }
        }
        p.vdot?.let {
            ThemedDivider(Modifier.padding(vertical = 8.dp))
            Text("VO2max estimate: ${"%.1f".format(it)}", color = t.text, fontSize = 15.sp)
            p.vdotFrom?.let { f -> Text("from your ${shortDistance(f.span)} on ${f.date.format(dayLabel)}", color = t.muted, fontSize = 12.sp) }
        }
        Text(
            "Riegel's formula over your best efforts of the last 6 months. Efforts inside ordinary runs are rarely all-out, so a real race would likely beat these.",
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun shortDistance(m: Double) = when (m) {
    1000.0 -> "1 km"; 1609.34 -> "mile"; 5000.0 -> "5K"; 10000.0 -> "10K"; 16093.4 -> "10 miles"; 21097.5 -> "half marathon"; 42195.0 -> "marathon"; 400.0 -> "400 m"
    else -> "%.0f m".format(m)
}

@Composable
private fun TrendCard(cs: CriticalSpeedScreen, fmt: Formatter) {
    val t = Fitness.theme
    if (cs.trend.size < 2) return
    ThemedCard {
        // Faster is higher, so pace is drawn negated and printed as m:ss.
        val xs = DoubleArray(cs.trend.size) { it.toDouble() }
        val ys = DoubleArray(cs.trend.size) { -fmt.toPaceMin(cs.trend[it].cs) }
        LinesChart(
            "Critical pace over time (min/${fmt.distUnit})", listOf(ChartLine("Critical pace", xs, ys, t.chart.pace)),
            { cs.trend.getOrNull(it.toInt())?.date?.format(monthLabel) ?: "" }, { paceLabel(it) },
        )
        Text("Your critical pace as of the end of each month.", color = t.muted, fontSize = 12.sp)
    }
}

@Composable
private fun MixCard(weeks: List<WeekMix>, counts: Map<RunType, Int>, km: Map<RunType, Double>, fmt: Formatter) {
    val t = Fitness.theme
    val total = counts.values.sum()
    if (total == 0) {
        ThemedCard { Text("No runs in the last 26 weeks.", color = t.muted, fontSize = 13.sp) }
        return
    }
    ThemedCard {
        Text("Weekly distance by type, last ${weeks.size} weeks", color = t.muted, fontSize = 12.sp)
        StackedBars(weeks, fmt, Modifier.fillMaxWidth().height(150.dp).padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RunTypeStyle.ORDER.filter { (counts[it] ?: 0) > 0 }.forEach {
                Text("● ${RunTypeStyle.label(it)}", color = RunTypeStyle.color(it), fontSize = 12.sp)
            }
        }
        ThemedDivider(Modifier.padding(vertical = 8.dp))
        RunTypeStyle.ORDER.filter { (counts[it] ?: 0) > 0 }.forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(RunTypeStyle.label(it), color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${counts[it]} runs  ·  ${fmt.distance((km[it] ?: 0.0) * 1000)}", color = t.muted, fontSize = 13.sp)
            }
        }
    }
}

/** One stacked bar per week, each type in its colour, scaled to the biggest week. */
@Composable
private fun StackedBars(weeks: List<WeekMix>, fmt: Formatter, modifier: Modifier) {
    val gridColor = Fitness.theme.divider
    val max = remember(weeks) { weeks.maxOf { w -> w.km.values.sum() }.coerceAtLeast(1.0) }
    Canvas(modifier) {
        val gap = 2f
        val bar = (size.width - gap * (weeks.size - 1)) / weeks.size
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        weeks.forEachIndexed { i, w ->
            var bottom = size.height
            for (type in RunTypeStyle.ORDER) {
                val v = w.km[type] ?: continue
                val h = (v / max * size.height).toFloat()
                drawRect(RunTypeStyle.color(type), Offset(i * (bar + gap), bottom - h), Size(bar, h))
                bottom -= h
            }
        }
    }
}

@Composable
private fun EfficiencyCard(vm: TrainingViewModel, s: app.fitness.core.EfficiencySeries, fmt: Formatter) {
    val t = Fitness.theme
    if (s.sport == null || s.points.size < 2) {
        ThemedCard { Text("Efficiency needs activities with heart rate and speed (or power) of at least 20 minutes.", color = t.muted, fontSize = 13.sp) }
        return
    }
    if (s.sports.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.sports.forEach { FilterPill(Labels.sportName(it), it == s.sport, { vm.selectEfficiencySport(it) }) }
    }
    val unit = if (s.basis == "power") "watts per bpm" else "metres per minute per bpm"
    ThemedCard {
        val xs = DoubleArray(s.points.size) { s.points[it].date.toEpochDay().toDouble() }
        // Single runs jump around a lot; draw a centred moving average so the direction is readable.
        val half = maxOf(2, s.points.size / 80)
        val smooth = DoubleArray(s.points.size) { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(s.points.size - 1, i + half)
            (lo..hi).sumOf { s.points[it].efficiency } / (hi - lo + 1)
        }
        LinesChart(
            "Efficiency factor, smoothed ($unit)", listOf(ChartLine("Efficiency", xs, smooth, t.chart.heartRate)),
            { LocalDate.ofEpochDay(it.toLong()).format(monthLabel) }, { "%.2f".format(it) },
        )
        val med = s.points.mapNotNull { it.decoupling }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        s.trendPctPerMonth?.let {
            Text(
                "Trend: ${"%+.1f".format(it)}% per month. Higher means more output for each heartbeat, so a stronger aerobic engine.",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        med?.let {
            Text(
                "Typical decoupling ${"%.1f".format(it)}%: how far efficiency falls between the first and second half. Under about 5% is a well-aerobic effort.",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun paceLabel(negMinutes: Double): String {
    val total = (abs(negMinutes) * 60).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
