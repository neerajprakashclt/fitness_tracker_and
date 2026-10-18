package app.fitness.android.ui.training

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitness.android.ui.charts.ChartLine
import app.fitness.android.ui.charts.LinesChart
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.StatTile
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.DayLoad
import app.fitness.core.LoadSafety
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val axisDate = DateTimeFormatter.ofPattern("MMM yy")

/**
 * Whether training load is rising faster than is usual: the ratio of the last week to the last four, how fast fitness rose,
 * and how uniform the week was. Rules of thumb from group studies, not a prediction for one person and not medical advice.
 */
@Composable
fun LoadSafetyCard(daily: List<DayLoad>) {
    val t = Fitness.theme
    val report = remember(daily) { LoadSafety.analysis(daily) } ?: return
    val now = report.now
    SectionHeader("Load safety")
    ThemedCard {
        val (label, color) = when (now.band) {
            "high" -> "High" to t.danger
            "caution" -> "Caution" to Color(0xFFF59F00)
            "optimal" -> "Optimal" to t.accent
            "low" -> "Low" to t.muted
            else -> "Not enough history" to t.muted
        }
        Row(Modifier.fillMaxWidth()) {
            StatTile("Last 7 days vs 4 weeks", now.acwr?.let { "%.2f×".format(it) } ?: "–", Modifier.weight(1.3f))
            Text(label, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(top = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            StatTile("Fitness change, 7 days", now.ramp?.let { "%+.1f".format(it) } ?: "–", Modifier.weight(1f))
            StatTile("Monotony", now.monotony?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f))
            StatTile("Rest days of 7", now.restDays?.toString() ?: "–", Modifier.weight(1f))
        }
        if (report.warnings.isEmpty()) {
            Text("Nothing to flag: your recent load is within the usual range.", color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        } else {
            ThemedDivider(Modifier.padding(vertical = 10.dp))
            report.warnings.forEach { w ->
                Text(
                    "● ${w.message}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp),
                    color = when (w.level) { "high" -> t.danger; "caution" -> Color(0xFFF59F00); else -> t.muted },
                )
            }
        }
    }
    val shown = report.series.filter { it.acwr != null }
    if (shown.size > 2) ThemedCard {
        val xs = DoubleArray(shown.size) { shown[it].date.toEpochDay().toDouble() }
        LinesChart(
            "Acute:chronic load ratio, last ${report.series.size} days",
            listOf(
                ChartLine("Ratio", xs, DoubleArray(shown.size) { shown[it].acwr!! }, t.chart.heartRate),
                ChartLine("1.3", xs, DoubleArray(shown.size) { LoadSafety.ACWR_CAUTION }, t.muted.copy(alpha = 0.6f)),
                ChartLine("0.8", xs, DoubleArray(shown.size) { LoadSafety.ACWR_SWEET_LOW }, t.muted.copy(alpha = 0.6f)),
            ),
            { LocalDate.ofEpochDay(it.toLong()).format(axisDate) }, { "%.1f".format(it) }, height = 160,
        )
        Text(
            "The grey lines mark 0.8 and 1.3, the usual sweet spot; above 1.5 is linked with more injuries. A ratio below 0.8 is a drop in training. " +
                "These are rules of thumb from group studies, not a prediction for you and not medical advice.",
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
