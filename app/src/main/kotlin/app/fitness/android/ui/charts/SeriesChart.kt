package app.fitness.android.ui.charts

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
import app.fitness.core.Series
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

/**
 * One line panel of the activity's stacked charts. Touching it reports the x (seconds into the activity) under the finger
 * through [onScrub], and null when released, so the map marker can follow. Series are pre-thinned by `ChartSeries`.
 */
@Composable
fun SeriesChart(
    title: String,
    series: Series,
    color: Color,
    filled: Boolean,
    yFormat: (Double) -> String,
    xFormat: (Double) -> String,
    onScrub: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Fitness.theme
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) { producer.runTransaction { lineSeries { series(series.x.toList(), series.y.toList()) } } }
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
    // Fit the y axis to the data (with a little headroom) instead of starting at zero, which flattens a heart-rate line.
    val range = remember(series) {
        val lo = series.y.minOrNull() ?: 0.0
        val hi = series.y.maxOrNull() ?: 1.0
        val pad = maxOf((hi - lo) * 0.08, 1e-3)
        CartesianLayerRangeProvider.fixed(minY = lo - pad, maxY = hi + pad)
    }
    Column(modifier) {
        Text(title, color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(line), rangeProvider = range),
                startAxis = VerticalAxis.rememberStart(valueFormatter = { _, v, _ -> yFormat(v) }),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, v, _ -> xFormat(v) },
                    itemPlacer = remember(series.size) { HorizontalAxis.ItemPlacer.aligned(spacing = { maxOf(1, series.size / 4) }) },
                ),
                marker = rememberDefaultCartesianMarker(label = rememberTextComponent()),
                markerVisibilityListener = listener,
            ),
            modelProducer = producer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),       // fit the whole activity into the width
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}
