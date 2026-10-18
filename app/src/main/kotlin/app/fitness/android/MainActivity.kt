package app.fitness.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fitness.android.spikes.ActivityChartsSpike
import app.fitness.android.spikes.CurveSpike
import app.fitness.android.spikes.MapLibreView
import app.fitness.android.spikes.RouteMapHandle
import app.fitness.android.spikes.SpikeData
import app.fitness.android.spikes.StackedColumnsSpike
import app.fitness.android.spikes.addActivityRoute
import app.fitness.android.spikes.addHeatmap

/** Phase 0: a throwaway host for the three spikes (charts, curves/columns, map). Replaced by the real app in phase 1. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = SpikeData.load(this)
        val startTab = intent.getIntExtra("tab", 0)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var tab by remember { mutableIntStateOf(startTab) }
                    val handle = remember { RouteMapHandle() }
                    Column(Modifier.statusBarsPadding()) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            listOf("Activity", "Curve", "Weekly", "Heatmap").forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                            }
                        }
                        when (tab) {
                            0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                Box(Modifier.fillMaxWidth().height(220.dp)) {
                                    MapLibreView(Modifier.fillMaxSize()) { map, style ->
                                        addActivityRoute(map, style, data.lat, data.lon, handle)
                                    }
                                }
                                ActivityChartsSpike(data) { i -> if (i != null && !data.lat[i].isNaN()) handle.moveMarker(data.lat[i], data.lon[i]) }
                            }
                            1 -> CurveSpike(data)
                            2 -> StackedColumnsSpike(data)
                            else -> MapLibreView(Modifier.fillMaxSize()) { map, style -> addHeatmap(map, style, data.routes) }
                        }
                    }
                }
            }
        }
    }
}
