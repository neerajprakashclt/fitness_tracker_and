package app.fitness.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.Formatter

/** Time in each zone as a bar per zone: its name and range on the left, the time and share on the right. */
@Composable
fun ZoneBars(names: List<String>, seconds: DoubleArray, range: (Int) -> String, modifier: Modifier = Modifier) {
    val t = Fitness.theme
    val total = seconds.sum().takeIf { it > 0 } ?: return
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        for (i in names.indices) {
            val fraction = (seconds[i] / total).toFloat()
            val color = t.chart.zones.getOrElse(i) { t.accent }
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(names[i], color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(range(i), color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
                    Text("${Formatter.duration(seconds[i])}  ${"%.0f".format(fraction * 100)}%", color = t.text, fontSize = 13.sp)
                }
                Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.divider)) {
                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                }
            }
        }
    }
}
