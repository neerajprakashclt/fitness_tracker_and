package app.fitness.android.spikes

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import app.fitness.core.Polyline

private const val TAG = "Spike"

/** Raster OSM tiles on a dark background; "None" would be the same style without the raster layer. */
private val OSM_STYLE = """
{"version":8,
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,
   "attribution":"OpenStreetMap contributors"}},
 "layers":[{"id":"bg","type":"background","paint":{"background-color":"#1f2328"}},
           {"id":"osm","type":"raster","source":"osm","paint":{"raster-opacity":0.55,"raster-saturation":-0.6}}]}
""".trimIndent()

/** A MapLibre map whose lifecycle follows the composition's lifecycle owner. */
@Composable
fun MapLibreView(modifier: Modifier = Modifier, onStyle: (MapLibreMap, Style) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
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
        // Adding an observer to an already-resumed lifecycle replays onCreate, onStart and onResume to it.
        mapView.getMapAsync { map ->
            map.setPrefetchesTiles(false)       // OSM tile policy: no prefetching
            map.setStyle(Style.Builder().fromJson(OSM_STYLE)) { style -> onStyle(map, style) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun lineString(points: List<DoubleArray>): String =
    points.joinToString(",", "[", "]") { "[${it[1]},${it[0]}]" }          // GeoJSON is [lon, lat]

/** The activity's route with a marker that the chart scrubber moves. */
class RouteMapHandle {
    internal var markerSource: GeoJsonSource? = null
    fun moveMarker(lat: Double, lon: Double) {
        markerSource?.setGeoJson("""{"type":"Point","coordinates":[$lon,$lat]}""")
    }
}

fun addActivityRoute(map: MapLibreMap, style: Style, lat: DoubleArray, lon: DoubleArray, handle: RouteMapHandle) {
    val pts = lat.indices.filter { !lat[it].isNaN() && !lon[it].isNaN() }.map { doubleArrayOf(lat[it], lon[it]) }
    style.addSource(GeoJsonSource("route", """{"type":"LineString","coordinates":${lineString(pts)}}"""))
    style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#ff8a4c"), lineWidth(4f)))
    val marker = GeoJsonSource("marker", """{"type":"Point","coordinates":[${pts[0][1]},${pts[0][0]}]}""")
    style.addSource(marker)
    style.addLayer(CircleLayer("marker-dot", "marker").withProperties(
        circleRadius(7f), circleColor("#ffffff"), circleStrokeColor("#e8590c"), circleStrokeWidth(3f)))
    handle.markerSource = marker
    val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it[0], it[1])) } }.build()
    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b, 40))
}

/**
 * Every route in the history as one GeoJSON source and one line layer. The GeoJSON text is built on a worker thread
 * and handed to the map on the main thread; [onDone] receives (points, milliseconds to build).
 */
fun addHeatmap(map: MapLibreMap, style: Style, routes: List<SpikeData.Route>, onDone: (Int, Long) -> Unit = { _, _ -> }) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    Thread {
        val t0 = System.nanoTime()
        var points = 0
        var segments = 0
        val sb = StringBuilder(4 * 1024 * 1024)
        sb.append("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (r in routes) {
            if (r.segments.isEmpty()) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","properties":{"sport":"${r.sport}"},"geometry":{"type":"MultiLineString","coordinates":[""")
            r.segments.forEachIndexed { i, seg ->
                if (i > 0) sb.append(',')
                sb.append('[')
                points += Polyline.appendGeoJson(seg, sb)
                sb.append(']')
                segments++
            }
            sb.append("]}}")
        }
        sb.append("]}")
        val built = (System.nanoTime() - t0) / 1_000_000
        val text = sb.toString()
        main.post {
            val t1 = System.nanoTime()
            style.addSource(GeoJsonSource("routes", text))
            style.addLayer(LineLayer("routes-line", "routes").withProperties(
                lineColor("#ff6b35"), lineWidth(1.6f), lineOpacity(0.35f)))
            val c = routes.firstNotNullOfOrNull { it.segments.firstOrNull()?.let(Polyline::decode)?.firstOrNull() }
            if (c != null) map.cameraPosition = CameraPosition.Builder().target(LatLng(c[0], c[1])).zoom(11.0).build()
            Log.i(TAG, "heatmap: ${routes.size} routes, $segments segments, $points points; build $built ms (worker thread), " +
                "hand-over ${(System.nanoTime() - t1) / 1_000_000} ms (main thread), geojson ${text.length / 1024} KB")
            onDone(points, built)
        }
    }.start()
}

@Suppress("unused")
private fun noop(c: Context) = Unit
