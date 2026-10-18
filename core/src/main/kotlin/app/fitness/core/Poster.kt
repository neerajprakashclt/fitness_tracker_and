package app.fitness.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.cos
import kotlin.math.round

/** One personal-best series: each time the best effort over a standard distance was beaten (PC: `volume.records_timeline`). */
class RecordSeries(val distance: Double, val name: String, val records: List<Record>) {
    class Record(val date: LocalDate, val seconds: Double, val key: String)
}

/** The parts of the PC's `volume.py` the poster needs. */
object Volume {
    val RECORD_DISTANCES = listOf(1000.0 to "1 km", 1609.34 to "1 mile", 5000.0 to "5K", 10000.0 to "10K", 21097.5 to "Half marathon", 42195.0 to "Marathon")

    /** [efforts] must be in start order. */
    fun recordsTimeline(efforts: List<Curves.Entry>): List<RecordSeries> = buildList {
        for ((dist, name) in RECORD_DISTANCES) {
            var best: Double? = null
            val records = ArrayList<RecordSeries.Record>()
            for (e in efforts) {
                val seconds = e.bests.distanceTime[dist] ?: continue
                if (best == null || seconds < best) { best = seconds; records += RecordSeries.Record(e.date, seconds, e.key) }
            }
            if (records.isNotEmpty()) add(RecordSeries(dist, name, records))
        }
    }

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The longest run of consecutive calendar weeks (Monday to Monday) with at least one activity. */
    fun longestStreakWeeks(dates: Collection<LocalDate>): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (w in dates.map(::weekStart).toSortedSet()) {
            run = if (prev != null && prev.plusWeeks(1) == w) run + 1 else 1
            if (run > best) best = run
            prev = w
        }
        return best
    }
}

class PosterRun(val id: String, val start: Instant, val distance: Double?, val duration: Double?, val load: Double?, val polylines: List<String>)

/**
 * The running-life poster's payload (PC: `poster.poster_data`), built for the PC's own `poster.html`, which draws it. Route
 * shapes carry no position: each is relative to its own start. Run type, pace-vs-threshold, repeated-route shapes and
 * city names need analytics that are not ported yet, so they are left empty and the poster skips what needs them.
 */
object Poster {
    private const val BURST_ROUTES = 400
    private const val BURST_MAX_POINTS = 40
    private const val EARTH_RADIUS = 6371000.0

    fun data(runs: List<PosterRun>, records: List<RecordSeries>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): JsonObject {
        val sorted = runs.sortedBy { it.start }
        fun r2(v: Double) = round(v * 100) / 100
        val rows = sorted.map { r ->
            val local = r.start.atZone(zone)
            val d = r.distance
            val t = r.duration
            buildJsonObject {
                put("id", r.id)
                put("date", local.toLocalDate().toString())
                put("km", r2((d ?: 0.0) / 1000))
                put("type", "unknown")
                put("mps", if (d != null && d != 0.0 && t != null && t != 0.0) JsonPrimitive(r2(d / t)) else JsonNull)
                put("hour", r2(local.hour + local.minute / 60.0))
                put("load", r.load?.let { JsonPrimitive(round(it * 10) / 10) } ?: JsonNull)
                put("csf", JsonNull)
            }
        }
        val kms = sorted.indices.map { rows[it]["km"].let { v -> (v as JsonPrimitive).content.toDouble() } }
        val dates = rows.map { LocalDate.parse((it["date"] as JsonPrimitive).content) }

        val weekKm = HashMap<LocalDate, Double>()
        for (i in dates.indices) weekKm.merge(Volume.weekStart(dates[i]), kms[i], Double::plus)
        val weeks = buildJsonArray {
            if (weekKm.isNotEmpty()) {
                var w = weekKm.keys.min()
                val last = Volume.weekStart(today)
                while (!w.isAfter(last)) {
                    add(buildJsonObject { put("w", w.toString()); put("km", round((weekKm[w] ?: 0.0) * 100) / 100); put("hard", 0.0) })
                    w = w.plusWeeks(1)
                }
            }
        }
        val byYear = HashMap<String, Double>()
        for (i in dates.indices) byYear.merge(dates[i].year.toString(), kms[i], Double::plus)
        val totalKm = kms.sum()
        val seconds = sorted.sumOf { it.duration ?: 0.0 }

        val burst = sorted.filter { it.polylines.isNotEmpty() }.takeLast(BURST_ROUTES).mapNotNull { r ->
            val shape = burst(r.polylines)
            if (shape.isEmpty()) null else buildJsonObject {
                put("runs", 1)
                put("shape", JsonArray(shape.map { seg -> JsonArray(seg.map { p -> JsonArray(listOf(JsonPrimitive(p[0]), JsonPrimitive(p[1]))) }) }))
                put("ids", buildJsonArray { add(JsonPrimitive(r.id)) })
            }
        }
        val recordRows = buildJsonArray {
            records.forEach { s -> s.records.forEach { rec ->
                add(buildJsonObject { put("date", rec.date.toString()); put("name", s.name); put("seconds", rec.seconds); put("id", rec.key) })
            } }
        }
        return buildJsonObject {
            put("sport", "running")
            put("runs", JsonArray(rows))
            put("weeks", weeks)
            put("records", recordRows)
            put("routes", JsonArray(emptyList()))
            put("total_routes", 0)
            put("starburst", JsonArray(burst))
            put("places", buildJsonObject { put("areas", JsonArray(emptyList())); put("countries", 0) })
            put("stats", buildJsonObject {
                put("runs", rows.size)
                put("km", round(totalKm * 10) / 10)
                put("hours", round(seconds / 3600 * 10) / 10)
                put("first", rows.firstOrNull()?.get("date") ?: JsonNull)
                put("last", rows.lastOrNull()?.get("date") ?: JsonNull)
                put("longest_km", kms.maxOrNull() ?: 0.0)
                put("biggest_week_km", weekKm.values.maxOrNull()?.let { round(it * 100) / 100 } ?: 0.0)
                val by = byYear.maxByOrNull { it.value }
                put("biggest_year", if (by == null) JsonNull else buildJsonArray { add(JsonPrimitive(by.key)); add(JsonPrimitive(by.value)) })
                put("longest_streak_weeks", Volume.longestStreakWeeks(dates))
                put("personal_bests", records.sumOf { it.records.size })
                put("type_km", buildJsonObject { if (rows.isNotEmpty()) put("unknown", round(totalKm * 10) / 10) })
            })
        }
    }

    /** A route as segments of [east, north] in whole metres from its own first point, north up (PC: `poster.burst`). */
    internal fun burst(polylines: List<String>): List<List<IntArray>> {
        val segments = polylines.map { Polyline.decode(it) }.filter { it.size >= 2 }
        if (segments.isEmpty()) return emptyList()
        val lat0 = segments[0][0][0]
        val lon0 = segments[0][0][1]
        val k = Math.toRadians(1.0) * EARTH_RADIUS
        val cosLat = cos(Math.toRadians(lat0))
        return segments.map { s ->
            val pts = if (s.size > BURST_MAX_POINTS) List(BURST_MAX_POINTS) { i -> s[round(i * (s.size - 1.0) / (BURST_MAX_POINTS - 1)).toInt()] } else s
            pts.map { p -> intArrayOf(round((p[1] - lon0) * cosLat * k).toInt(), round(-((p[0] - lat0) * k)).toInt()) }
        }
    }
}
