package app.fitness.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.Labels
import app.fitness.android.ui.activities.DeleteDialog
import app.fitness.android.ui.charts.SeriesChart
import app.fitness.android.ui.components.QuietButton
import app.fitness.android.ui.components.RouteThumbnail
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.StatTile
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.map.RouteMap
import app.fitness.android.ui.map.RouteMapHandle
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.ChartSeries
import app.fitness.core.Formatter
import app.fitness.core.Series
import app.fitness.data.ActivityDetail
import app.fitness.data.LapItem
import app.fitness.data.LoadInfo
import kotlin.math.abs

@Composable
fun DetailScreen(vm: DetailViewModel, fmt: Formatter, streetMap: Boolean, onBack: () -> Unit) {
    val t = Fitness.theme
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(t.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton(if (t.layout == LayoutStyle.TERMINAL) "<- back" else "‹ Activities", onBack)
            Box(Modifier.weight(1f))
            if (state is DetailState.Ready) QuietButton("Delete", { confirmDelete = true }, danger = true)
        }
        when (val s = state) {
            DetailState.Loading -> Unit
            DetailState.NotFound -> Text("Activity not found", color = t.muted, modifier = Modifier.padding(24.dp))
            is DetailState.Ready -> Body(s.detail, s.streams, s.load, fmt, streetMap)
        }
    }
    if (confirmDelete) DeleteDialog(1, onConfirm = { confirmDelete = false; vm.delete(onBack) }, onDismiss = { confirmDelete = false })
}

@Composable
private fun Body(d: ActivityDetail, streams: StreamDigest?, load: LoadInfo?, fmt: Formatter, streetMap: Boolean) {
    val t = Fitness.theme
    val a = d.activity
    val handle = remember { RouteMapHandle() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(
                if (t.layout == LayoutStyle.TERMINAL) "$ ${Labels.title(a).lowercase()}" else Labels.title(a),
                color = t.text, fontSize = if (t.layout == LayoutStyle.GROUPED) 28.sp else 24.sp, fontWeight = t.numeralWeight,
            )
            Text(Labels.dateTime(a.startTime), color = t.muted, fontSize = 14.sp)
            a.device?.let { Text("Recorded with $it", color = t.muted, fontSize = 13.sp) }
        }

        if (a.route.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(240.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(t.cornerRadius)).background(t.mapBackground)) {
                RouteMap(a.route, streetMap, handle, Modifier.fillMaxSize())
            }
        }

        ThemedCard {
            val rows = buildList {
                if (a.hasDistance) add("Distance" to fmt.distance(a.distance))
                add("Moving time" to Formatter.duration(a.duration))
                if (a.hasDistance) add(fmt.paceOrSpeedLabel(a.sport) to fmt.paceOrSpeed(a.sport, a.avgSpeed))
                if (a.hasDistance) add("Elevation" to fmt.elevation(a.elevationGain))
                streams?.avgHeartRate?.let { add("Avg HR" to "%.0f bpm".format(it)) }
                streams?.maxHeartRate?.let { add("Max HR" to "%.0f bpm".format(it)) }
                load?.normalizedPower?.let { add("Normalized power" to "%.0f W".format(it)) }
                load?.let { add("Training load · ${loadMethodLabel(it.method)}" to "%.0f".format(it.load)) }
            }
            rows.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Box(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    for (cell in pair) StatTile(cell.first, cell.second, Modifier.weight(1f))
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }

        if (streams != null) ChartsCard(a.sport, streams, fmt, handle)

        if (d.laps.isNotEmpty()) {
            SectionHeader("Laps")
            LapsTable(d.laps, a.sport, fmt)
        }

        Text("Imported ${d.importedAt.take(10)}", color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 24.dp))
    }
}

private fun loadMethodLabel(method: String) = when (method) { "power" -> "power"; "hr" -> "heart rate"; else -> "estimated" }

@Composable
private fun ChartsCard(sport: String, s: StreamDigest, fmt: Formatter, handle: RouteMapHandle) {
    val t = Fitness.theme
    val scrub: (Double?) -> Unit = remember(s, handle) {
        { x ->
            if (x != null && s.lat != null && s.lon != null) {
                val i = ChartSeries.indexAtTime(s.time, x)
                if (i >= 0 && !s.lat[i].isNaN() && !s.lon[i].isNaN()) handle.moveMarker(s.lat[i], s.lon[i])
            }
        }
    }
    val panels = remember(s, sport, fmt.units) { panels(sport, s, fmt) }
    if (panels.isEmpty()) return
    ThemedCard {
        panels.forEachIndexed { i, p ->
            if (i > 0) ThemedDivider(Modifier.padding(vertical = 4.dp))
            SeriesChart(
                title = p.title, series = p.series,
                color = when (p.kind) { Kind.HR -> t.chart.heartRate; Kind.PACE -> t.chart.pace; Kind.ELEVATION -> t.chart.elevation },
                filled = p.kind == Kind.ELEVATION, yFormat = p.format, xFormat = { Formatter.duration(it) }, onScrub = scrub,
            )
        }
    }
}

private enum class Kind { HR, PACE, ELEVATION }
private class Panel(val title: String, val kind: Kind, val series: Series, val format: (Double) -> String)

private fun panels(sport: String, s: StreamDigest, fmt: Formatter): List<Panel> = buildList {
    s.heartRate?.takeIf { !it.isEmpty() }?.let { add(Panel("Heart rate (bpm)", Kind.HR, it) { v -> "%.0f".format(v) }) }
    s.speed?.takeIf { !it.isEmpty() }?.let { sp ->
        if (sport in Formatter.PACE_SPORTS) {
            // Pace is drawn negated so faster is higher; labels print the absolute value as m:ss.
            val y = ChartSeries.clipToQuantiles(DoubleArray(sp.size) { -fmt.toPaceMin(sp.y[it]) })
            add(Panel("Pace (min/${fmt.distUnit})", Kind.PACE, Series(sp.x, y)) { v -> paceLabel(v) })
        } else {
            val y = ChartSeries.clipToQuantiles(DoubleArray(sp.size) { fmt.toSpeed(sp.y[it]) })
            add(Panel("Speed (${fmt.speedUnit})", Kind.PACE, Series(sp.x, y)) { v -> "%.0f".format(v) })
        }
    }
    s.altitude?.takeIf { !it.isEmpty() }?.let { al ->
        add(Panel("Elevation (${fmt.elevUnit})", Kind.ELEVATION, Series(al.x, DoubleArray(al.size) { fmt.toElev(al.y[it]) })) { v -> "%.0f".format(v) })
    }
}

private fun paceLabel(negMinutes: Double): String {
    val total = (abs(negMinutes) * 60).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun LapsTable(laps: List<LapItem>, sport: String, fmt: Formatter) {
    val t = Fitness.theme
    val hasHr = laps.any { it.avgHeartRate != null }
    ThemedCard {
        Row(Modifier.fillMaxWidth()) {
            for ((i, h) in listOfNotNull("Lap", "Time", "Distance", fmt.paceOrSpeedLabel(sport), if (hasHr) "HR" else null).withIndex()) {
                Text(h, color = t.muted, fontSize = 12.sp, modifier = Modifier.weight(if (i == 0) 0.6f else 1f))
            }
        }
        ThemedDivider(Modifier.padding(vertical = 6.dp))
        laps.forEach { lap ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("${lap.index + 1}", color = t.text, fontSize = 14.sp, modifier = Modifier.weight(0.6f))
                Text(Formatter.duration(lap.duration), color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmt.distance(lap.distance).substringBefore(' ') + " " + fmt.distUnit, color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmt.paceOrSpeed(sport, lap.speed), color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (hasHr) Text(lap.avgHeartRate?.let { "%.0f".format(it) } ?: "–", color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
