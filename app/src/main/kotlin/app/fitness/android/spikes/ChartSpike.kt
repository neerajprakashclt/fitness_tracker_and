package app.fitness.android.spikes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import kotlin.math.abs
import kotlin.math.floor

/** One line chart with a scrubber. [onScrub] receives the x value under the finger, or null when released. */
@Composable
fun SeriesChart(
    title: String, xs: List<Double>, ys: List<Double>, color: Color, filled: Boolean,
    yFormat: (Double) -> String, xFormat: (Double) -> String, onScrub: (Double?) -> Unit, modifier: Modifier = Modifier,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(xs, ys) { producer.runTransaction { lineSeries { series(xs, ys) } } }
    val listener = remember(onScrub) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) = onScrub(targets.firstOrNull()?.x)
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) = onScrub(targets.firstOrNull()?.x)
            override fun onHidden(marker: CartesianMarker) = onScrub(null)
        }
    }
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        areaFill = if (filled) LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.18f))) else null,
    )
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(line)),
                startAxis = VerticalAxis.rememberStart(valueFormatter = { _, v, _ -> yFormat(v) }),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, v, _ -> xFormat(v) },
                    itemPlacer = remember(xs.size) { HorizontalAxis.ItemPlacer.aligned(spacing = { maxOf(1, xs.size / 5) }) },
                ),
                marker = rememberDefaultCartesianMarker(label = rememberTextComponent()),
                markerVisibilityListener = listener,
            ),
            modelProducer = producer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),     // fit the whole run into the width
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}

fun fmtClock(seconds: Double): String {
    val s = seconds.toInt(); val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Pace (min/km) drawn as a *negative* number so faster is higher; the labels print the absolute value as m:ss. */
fun fmtPaceNegative(v: Double): String {
    val p = abs(v); val total = (p * 60).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** Keep at most [max] evenly spaced indices of the samples where [valid] holds (the full data stays in memory for maths). */
fun thin(n: Int, max: Int, valid: (Int) -> Boolean): List<Int> {
    val ok = (0 until n).filter(valid)
    if (ok.size <= max) return ok
    val step = ok.size.toDouble() / max
    return List(max) { ok[(it * step).toInt()] }
}

/** The activity page's stacked panels. Scrubbing any of them reports the sample index, to move a map marker. */
@Composable
fun ActivityChartsSpike(d: SpikeData, onIndex: (Int?) -> Unit) {
    val scrub: (Double?) -> Unit = { sx -> onIndex(sx?.let { v -> d.time.indexOfFirst { it >= v }.takeIf { it >= 0 } }) }
    val hr = remember(d) { thin(d.time.size, 500) { !d.heartRate[it].isNaN() && d.heartRate[it] > 0 } }
    val pace = remember(d) { thin(d.time.size, 500) { !d.speed[it].isNaN() && d.speed[it] > 0.5 } }
    val alt = remember(d) { thin(d.time.size, 500) { !d.altitude[it].isNaN() } }
    Column {
        SeriesChart("Heart rate (bpm)", hr.map { d.time[it] }, hr.map { d.heartRate[it] }, Color(0xFFFA5252), false,
            { "%.0f".format(it) }, ::fmtClock, scrub)
        // Pace drawn as a negative number so faster is higher; labels print the absolute value as m:ss.
        SeriesChart("Pace (min/km)", pace.map { d.time[it] }, pace.map { -(1000.0 / d.speed[it]) / 60.0 }, Color(0xFF1C7ED6), false,
            { fmtPaceNegative(it) }, ::fmtClock, scrub)
        SeriesChart("Elevation (m)", alt.map { d.time[it] }, alt.map { d.altitude[it] }, Color(0xFF51CF66), true,
            { "%.0f".format(it) }, ::fmtClock, scrub)
    }
    Text("plotted ${hr.size} of ${d.time.size} samples (full data kept for calculations)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
}

/** The pace/power curve: x is the index into the standard durations list, so ticks land on 1 min, 5 min, 1 h ... */
@Composable
fun CurveSpike(d: SpikeData) {
    val producer = remember { CartesianChartModelProducer() }
    val spans = d.curve.map { it.first }
    LaunchedEffect(d) { producer.runTransaction { lineSeries { series(spans.indices.map { it.toDouble() }, d.curve.map { 1000.0 / it.second / 60 * -1 }) } } }
    val xFmt = CartesianValueFormatter { _, v, _ ->
        val s = spans.getOrNull(floor(v + 0.5).toInt()) ?: return@CartesianValueFormatter ""
        when { s < 60 -> "%.0fs".format(s); s < 3600 -> "%.0fm".format(s / 60); else -> "%.0fh".format(s / 3600) }
    }
    val line = LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(Color(0xFFFF8A4C))))
    Column {
        Text("Pace curve: best pace by duration (ordinal x axis, ${spans.size} durations)", modifier = Modifier.padding(8.dp))
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(line)),
                startAxis = VerticalAxis.rememberStart(valueFormatter = { _, v, _ -> fmtPaceNegative(v) }),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = xFmt),
                marker = rememberDefaultCartesianMarker(label = rememberTextComponent()),
            ),
            modelProducer = producer, scrollState = rememberVicoScrollState(scrollEnabled = false),
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

/** Weekly time in each HR zone as stacked columns (the Fitness page's zone chart). */
@Composable
fun StackedColumnsSpike(d: SpikeData) {
    val producer = remember { CartesianChartModelProducer() }
    val zoneColors = listOf(0xFF74C0FC, 0xFF51CF66, 0xFFFCC419, 0xFFFF922B, 0xFFFA5252).map { Color(it) }
    LaunchedEffect(d) {
        producer.runTransaction {
            columnSeries { for (z in d.zoneNames.indices) series(d.weeks.map { (it.second.getOrElse(z) { 0.0 }) / 3600.0 }) }
        }
    }
    val columns = zoneColors.map { rememberLineComponent(fill = Fill(it), thickness = 8.dp) }
    Column {
        Text("Weekly hours in each HR zone, stacked (${d.weeks.size} weeks)", modifier = Modifier.padding(8.dp))
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(columns),
                    mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = { _, v, _ -> "%.0f h".format(v) }),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, v, _ -> d.weeks.getOrNull(v.toInt())?.first?.substring(5) ?: "" },
                    itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { 4 }),
                ),
            ),
            modelProducer = producer, scrollState = rememberVicoScrollState(scrollEnabled = false),
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
        Row(Modifier.padding(8.dp)) { d.zoneNames.forEachIndexed { i, n -> Text("■ $n  ", color = zoneColors[i], style = MaterialTheme.typography.labelSmall) } }
    }
}
