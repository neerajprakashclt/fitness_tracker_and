package app.fitness.android.ui.training

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.Labels
import app.fitness.android.ui.charts.StackedBarChart
import app.fitness.android.ui.components.FilterPill
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ZoneBars
import app.fitness.android.ui.log.ZoneState
import app.fitness.android.ui.log.ZonesViewModel
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.Formatter
import app.fitness.core.Zones
import java.time.format.DateTimeFormatter

private val weekLabel = DateTimeFormatter.ofPattern("d MMM yy")

/** Heart-rate zones by week: how the time you spend training splits across five zones, and what that mix is called. */
@Composable
fun ZonesTab(vm: ZonesViewModel) {
    val t = Fitness.theme
    val state by vm.state.collectAsStateWithLifecycle()
    val sport by vm.sportFilter.collectAsStateWithLifecycle()
    val weeksShown by vm.weeksShown.collectAsStateWithLifecycle()
    val s = state
    if (s == null) { Text("Loading…", color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp)); return }
    if (s is ZoneState.Empty && sport == null) {
        Text("No heart-rate data yet. Zones come from activities recorded with a heart-rate sensor, and need a maximum heart rate (set in Settings, or estimated from your data).",
            color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        return
    }
    val sports = (s as? ZoneState.Ready)?.sports ?: listOfNotNull(sport)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill("All", sport == null, { vm.setSport(null) })
        sports.forEach { FilterPill(Labels.sportName(it), sport == it, { vm.setSport(it) }) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("12w" to 12, "26w" to 26, "1y" to 52, "All" to Int.MAX_VALUE).forEach { (label, n) -> FilterPill(label, weeksShown == n, { vm.setWeeks(n) }) }
    }
    if (s !is ZoneState.Ready) { Text("No heart-rate data for this sport.", color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp)); return }

    val report = s.report
    val shown = report.weeks.takeLast(minOf(weeksShown, report.weeks.size))
    val maxHr = report.maxHr
    val lows = Zones.FLOORS.map { kotlin.math.round(maxHr * it) }
    ThemedCard {
        Text("Hours per week in each zone", color = t.muted, fontSize = 12.sp)
        StackedBarChart(
            shown.map { w -> DoubleArray(w.seconds.size) { w.seconds[it] / 3600 } }, t.chart.zones,
            shown.first().weekStart.format(weekLabel), shown.last().weekStart.format(weekLabel), { "%.1f h".format(it) },
            Modifier.padding(top = 6.dp),
        )
        Text("Zones are fractions of a maximum heart rate of ${"%.0f".format(maxHr)} bpm.", color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }

    // Totals over the weeks shown.
    val totals = DoubleArray(Zones.FLOORS.size) { i -> shown.sumOf { it.seconds[i] } }
    SectionHeader("Time in each zone")
    ThemedCard {
        ZoneBars(Zones.NAMES, totals, { k ->
            when (k) { 0 -> "below ${"%.0f".format(lows[1])}"; Zones.NAMES.lastIndex -> "${"%.0f".format(lows[k])}+ bpm"; else -> "${"%.0f".format(lows[k])}–${"%.0f".format(lows[k + 1])} bpm" }
        })
    }
    val pol = app.fitness.core.WeeklyZoneReport.polarization(totals)
    if (pol != null) {
        SectionHeader("Your training mix")
        ThemedCard {
            Text(pol.label.replaceFirstChar { it.uppercase() }, color = t.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${"%.0f".format(pol.easy * 100)}% easy (zones 1 and 2), ${"%.0f".format(pol.moderate * 100)}% moderate (zone 3), ${"%.0f".format(pol.hard * 100)}% hard (zones 4 and 5)",
                color = t.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                when (pol.label) {
                    "polarized" -> "Mostly easy with real hard efforts and little in between: the pattern many endurance coaches recommend."
                    "pyramidal" -> "Mostly easy, then moderate, then a little hard: a common and sustainable shape."
                    "moderate" -> "A lot of time at a moderate effort, which is tiring without being hard enough to force big gains."
                    else -> "More than half your time is in the upper zones. That works for a while but leaves little room to recover."
                },
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
