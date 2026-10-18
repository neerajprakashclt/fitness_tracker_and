package app.fitness.android.ui.map

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.Polyline
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource

/** OSM's tile usage policy asks for a User-Agent that identifies the app; the library default does not. */
private const val USER_AGENT = "FitnessTrackerAndroid/0.1 (personal sideloaded app; https://github.com/neerajprakashclt/fitness_tracker_and)"

private var clientInstalled = false

private fun installHttpClient() {
    if (clientInstalled) return
    clientInstalled = true
    HttpRequestUtil.setOkHttpClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }.build()
    )
}

private fun Color.hex() = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * The style: a themed background and, only when [streetMap] is on, OpenStreetMap raster tiles. With it off the map is the
 * route on a plain colour and makes no network requests at all.
 */
private fun styleJson(background: Color, streetMap: Boolean, dark: Boolean): String {
    val bg = """{"id":"bg","type":"background","paint":{"background-color":"${background.hex()}"}}"""
    if (!streetMap) return """{"version":8,"sources":{},"layers":[$bg]}"""
    val opacity = if (dark) 0.55 else 0.9
    val saturation = if (dark) -0.6 else -0.2
    return """{"version":8,
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
 "layers":[$bg,{"id":"osm","type":"raster","source":"osm","paint":{"raster-opacity":$opacity,"raster-saturation":$saturation}}]}"""
}

/** Lets the chart scrubber move a dot along the route. */
class RouteMapHandle {
    internal var markerSource: GeoJsonSource? = null
    fun moveMarker(lat: Double, lon: Double) {
        markerSource?.setGeoJson("""{"type":"Point","coordinates":[$lon,$lat]}""")
    }
}

/** The activity's route on a MapLibre map that follows the composition's lifecycle. */
@Composable
fun RouteMap(
    route: List<String>,
    streetMap: Boolean,
    handle: RouteMapHandle,
    modifier: Modifier = Modifier,
) {
    val t = Fitness.theme
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A destroyed MapView cannot be reused, so a change of route, tiles or theme builds a new one.
    val mapView = remember(route, streetMap, t) {
        MapLibre.getInstance(context)
        installHttpClient()
        MapView(context).also { it.layoutParams = ViewGroup.LayoutParams(-1, -1) }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = mapView.onCreate(null)
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        mapView.getMapAsync { map ->
            map.setPrefetchesTiles(false)           // OSM tile policy: no prefetching
            map.setStyle(Style.Builder().fromJson(styleJson(t.mapBackground, streetMap, t.dark))) { style -> drawRoute(map, style, route, t.chart.route, t.surface, handle) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            handle.markerSource = null
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (streetMap) Text("© OpenStreetMap contributors", color = t.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

private fun drawRoute(map: MapLibreMap, style: Style, route: List<String>, line: Color, markerFill: Color, handle: RouteMapHandle) {
    val segments = route.map { Polyline.decode(it) }.filter { it.size >= 2 }
    if (segments.isEmpty()) return
    val coords = segments.joinToString(",", "[", "]") { seg -> seg.joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" } }
    style.addSource(GeoJsonSource("route", """{"type":"MultiLineString","coordinates":$coords}"""))
    style.addLayer(LineLayer("route-line", "route").withProperties(
        lineColor(line.hex()), lineWidth(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    val start = segments.first().first()
    val marker = GeoJsonSource("marker", """{"type":"Point","coordinates":[${start[1]},${start[0]}]}""")
    style.addSource(marker)
    style.addLayer(CircleLayer("marker-dot", "marker").withProperties(
        circleRadius(7f), circleColor(markerFill.hex()), circleStrokeColor(line.hex()), circleStrokeWidth(3f)))
    handle.markerSource = marker
    val bounds = LatLngBounds.Builder().apply { segments.forEach { s -> s.forEach { include(LatLng(it[0], it[1])) } } }.build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
}
