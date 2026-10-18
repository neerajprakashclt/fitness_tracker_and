package app.fitness.android.ui.charts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitness.android.ui.theme.Fitness
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

/** One line of a [LinesChart]. [x] must be evenly spaced and shared by every line. */
class ChartLine(val name: String, val x: DoubleArray, val y: DoubleArray, val color: Color, val filled: Boolean = false)

/**
 * Several lines on one set of axes (the fitness and fatigue curves, a power curve). The y axis fits the data of all lines;
 * a legend names each line. Colours come from the theme at the call site.
 */
@Composable
fun LinesChart(
    title: String,
    lines: List<ChartLine>,
    xFormat: (Double) -> String,
    yFormat: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Int = 180,
) {
    val t = Fitness.theme
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(lines) {
        if (lines.none { it.x.isNotEmpty() }) return@LaunchedEffect
        producer.runTransaction { lineSeries { lines.forEach { series(it.x.toList(), it.y.toList()) } } }
    }
    val drawn = lines.map { l ->
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(l.color)),
            areaFill = if (l.filled) LineCartesianLayer.AreaFill.single(Fill(l.color.copy(alpha = 0.18f))) else null,
        )
    }
    val range = remember(lines) {
        val all = lines.flatMap { it.y.asList() }
        val lo = all.minOrNull() ?: 0.0
        val hi = all.maxOrNull() ?: 1.0
        val pad = maxOf((hi - lo) * 0.08, 1e-3)
        CartesianLayerRangeProvider.fixed(minY = lo - pad, maxY = hi + pad)
    }
    val points = lines.maxOfOrNull { it.x.size } ?: 0
    Column(modifier) {
        Text(title, color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        if (lines.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp)) {
            lines.forEach { Text("● ${it.name}   ", color = it.color, fontSize = 12.sp, maxLines = 1) }
        }
        if (points == 0) {
            Text("No data yet", color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 24.dp))
            return@Column
        }
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(drawn), rangeProvider = range),
                startAxis = VerticalAxis.rememberStart(valueFormatter = { _, v, _ -> yFormat(v) }),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, v, _ -> xFormat(v) },
                    itemPlacer = remember(points) { HorizontalAxis.ItemPlacer.aligned(spacing = { if (points <= 12) 1 else maxOf(1, points / 4) }) },
                ),
                marker = rememberDefaultCartesianMarker(label = rememberTextComponent()),
            ),
            modelProducer = producer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            modifier = Modifier.fillMaxWidth().height(height.dp),
        )
    }
}
