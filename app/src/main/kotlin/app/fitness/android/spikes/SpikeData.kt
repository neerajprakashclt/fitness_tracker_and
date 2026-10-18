package app.fitness.android.spikes

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real data exported from the PC app for the phase-0 spikes (debug builds only; see tools/export_spike_data.py). */
class SpikeData(
    val time: DoubleArray, val heartRate: DoubleArray, val speed: DoubleArray, val altitude: DoubleArray,
    val lat: DoubleArray, val lon: DoubleArray,
    val curve: List<Pair<Double, Double>>,           // (seconds, best m/s)
    val weeks: List<Pair<String, DoubleArray>>,      // (week start, seconds in each zone)
    val zoneNames: List<String>,
    val routes: List<Route>,
) {
    class Route(val sport: String, val segments: List<String>)

    companion object {
        private fun JsonElement?.doubles(): DoubleArray =
            if (this == null || this is JsonNull) DoubleArray(0)
            else (this as JsonArray).map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.doubleOrNull ?: Double.NaN }.toDoubleArray()

        fun load(context: Context): SpikeData {
            val text = context.assets.open("spike-data.json").bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(text).jsonObject
            val a = root.getValue("activity").jsonObject
            return SpikeData(
                time = a["time"].doubles(), heartRate = a["heart_rate"].doubles(), speed = a["speed"].doubles(),
                altitude = a["altitude"].doubles(), lat = a["lat"].doubles(), lon = a["lon"].doubles(),
                curve = root.getValue("speed_curve").jsonArray.map {
                    val o = it.jsonObject; o.getValue("span").jsonPrimitive.content.toDouble() to o.getValue("value").jsonPrimitive.content.toDouble()
                },
                weeks = root.getValue("weekly_zones").jsonArray.map {
                    val o: JsonObject = it.jsonObject
                    o.getValue("week_start").jsonPrimitive.content to o["seconds"].doubles()
                },
                zoneNames = root.getValue("zone_names").jsonArray.map { it.jsonPrimitive.content },
                routes = root.getValue("routes").jsonArray.map {
                    val o = it.jsonObject
                    Route(o.getValue("sport").jsonPrimitive.content, o.getValue("segments").jsonArray.map { s -> s.jsonPrimitive.content })
                },
            )
        }
    }
}
