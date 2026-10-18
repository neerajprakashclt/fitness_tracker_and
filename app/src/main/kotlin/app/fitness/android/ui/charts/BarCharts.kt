package app.fitness.android.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitness.android.ui.theme.Fitness

/**
 * One bar per period, each split into stacked parts (a single part for a plain bar chart), scaled to the biggest bar. Drawn
 * on a canvas from the theme's colours; [firstLabel] and [lastLabel] name the ends of the x axis and [maxLabel] the top of the y axis.
 */
@Composable
fun StackedBarChart(
    parts: List<DoubleArray>,
    colors: List<Color>,
    firstLabel: String,
    lastLabel: String,
    maxLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Int = 150,
) {
    val t = Fitness.theme
    val max = remember(parts) { parts.maxOfOrNull { it.sum() }?.takeIf { it > 0 } ?: 1.0 }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(maxLabel(max), color = t.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp).padding(top = 2.dp)) {
            val gap = if (parts.size > 60) 0.5f else 2f
            val bar = ((size.width - gap * (parts.size - 1)) / parts.size).coerceAtLeast(1f)
            drawLine(t.divider, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            drawLine(t.divider.copy(alpha = 0.5f), Offset(0f, 0f), Offset(size.width, 0f), 1f)
            parts.forEachIndexed { i, stack ->
                var bottom = size.height
                for ((k, v) in stack.withIndex()) {
                    if (v <= 0) continue
                    val h = (v / max * size.height).toFloat()
                    drawRect(colors[k % colors.size], Offset(i * (bar + gap), bottom - h), Size(bar, h))
                    bottom -= h
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(firstLabel, color = t.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(lastLabel, color = t.muted, fontSize = 11.sp)
        }
    }
}
