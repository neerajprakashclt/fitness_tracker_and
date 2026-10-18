package app.fitness.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import app.fitness.core.Polyline
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A route drawn on a plain canvas from the stored polyline: no map, no tiles, no network. This is what the feed shows
 * for each activity, and the detail screen shows while the map loads or when the street map is off.
 */
@Composable
fun RouteThumbnail(route: List<String>, color: Color, modifier: Modifier = Modifier, strokeWidth: Float = 4f) {
    val segments = remember(route) { route.map { Polyline.decode(it) }.filter { it.size >= 2 } }
    Canvas(modifier.fillMaxSize()) {
        if (segments.isEmpty()) return@Canvas
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (s in segments) for (p in s) {
            minLat = min(minLat, p[0]); maxLat = max(maxLat, p[0]); minLon = min(minLon, p[1]); maxLon = max(maxLon, p[1])
        }
        // Longitude degrees are shorter than latitude degrees away from the equator.
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = max((maxLon - minLon) * k, 1e-6)
        val h = max(maxLat - minLat, 1e-6)
        val pad = strokeWidth * 2
        val scale = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        val path = Path()
        for (s in segments) {
            s.forEachIndexed { i, p ->
                val x = (ox + (p[1] - minLon) * k * scale).toFloat()
                val y = (oy + (maxLat - p[0]) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val first = segments.first().first()
        drawCircle(color, strokeWidth * 1.4f, Offset((ox + (first[1] - minLon) * k * scale).toFloat(), (oy + (maxLat - first[0]) * scale).toFloat()))
    }
}
