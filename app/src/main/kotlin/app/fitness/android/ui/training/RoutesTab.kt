package app.fitness.android.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.components.RouteThumbnail
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.Formatter
import app.fitness.core.RouteReport
import java.time.format.DateTimeFormatter

private val dayLabel = DateTimeFormatter.ofPattern("d MMM yyyy")

/** The routes you run again and again, and whether you are getting faster on them. */
@Composable
fun RoutesTab(vm: TrainingViewModel, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    LaunchedEffect(Unit) { vm.loadRoutes() }
    val routes by vm.routes.collectAsStateWithLifecycle()
    val list = routes
    if (list == null) { Text("Finding your routes…", color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp)); return }
    if (list.isEmpty()) {
        Text("No route has been run three times yet. Runs on the same path, starting from the same place, are grouped.", color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        return
    }
    list.forEach { RouteCard(it, fmt, onOpen) }
    Text(
        "Runs are grouped when they start close together, are about the same length and stay on the same path; the same loop the other way is a different route.",
        color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RouteCard(r: RouteReport, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    var open by rememberSaveable(r.key) { mutableStateOf(false) }
    ThemedCard(onClick = { open = !open }) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.size(84.dp)) { RouteThumbnail(r.polylines, t.chart.route) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("${r.runs.size} runs · ${fmt.distance(r.distance)}", color = t.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("${r.firstDate.format(dayLabel)} to ${r.lastDate.format(dayLabel)}", color = t.muted, fontSize = 12.sp)
                r.best?.let { Text("Best ${fmt.pace(it.speed)} on ${it.date.format(dayLabel)}", color = t.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                r.medianSpeed?.let { Text("Usually ${fmt.pace(it)}", color = t.muted, fontSize = 13.sp) }
            }
        }
        val speed = r.trend.speedPctPerYear
        val hr = r.trend.hrBpmPerYear
        Text(
            when {
                speed == null -> "A trend needs 8 or more runs over at least 90 days."
                else -> (if (speed >= 0) "Getting faster by " else "Getting slower by ") + "${"%.1f".format(kotlin.math.abs(speed))}% a year" +
                    (hr?.let { ", heart rate ${"%+.0f".format(it)} bpm a year" } ?: "")
            },
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        if (open) {
            ThemedDivider(Modifier.padding(vertical = 8.dp))
            r.runs.asReversed().forEach { run ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(run.key) }.padding(vertical = 6.dp)) {
                    Text(run.date.format(dayLabel), color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1.3f))
                    Text(fmt.pace(run.speed), color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(run.avgHr?.let { "%.0f bpm".format(it) } ?: "", color = t.muted, fontSize = 13.sp, modifier = Modifier.weight(0.8f))
                }
            }
        }
    }
}
