package app.fitness.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Do the ported analytics (stream metrics, load, CTL/ATL/TSB, best efforts, curves, estimated thresholds) give what the PC
 * app gives? Two levels: the 77 single files with the thresholds the exporter fixed, and the whole 14-year archive replayed
 * through the import pipeline and compared with its `full_history` fixture. Sports are taken from the PC database
 * (`seed/pc-activities.json`) where it has them, so the three old files the PC left "unknown" do not blur the comparison.
 */
class AnalyticsParityTest {
    private val none = object : ActivityLookup {
        override suspend fun exists(fileHash: String) = false
        override suspend fun candidates(sport: String, anySport: Boolean, fromDate: String, toDate: String) = emptyList<SimilarCandidate>()
    }

    private fun thresholds(): Thresholds {
        val f = Golden.root.obj("fixed_settings")
        return Thresholds(f["ftp"].num(), f["max_hr"].num(), f["resting_hr"].num(), f["threshold_hr"].num())
    }

    private fun histOf(json: String?): Map<Int, Double>? =
        json?.let { Json.parseToJsonElement(it).jsonObject.entries.associate { (k, v) -> k.toInt() to v.num()!! } }

    private fun compareBests(label: String, want: JsonObject, got: Bests, bad: Mismatches) {
        fun <K> side(name: String, w: JsonObject, g: Map<K, Double>, key: (String) -> K) {
            val wantMap = w.entries.associate { key(it.key) to it.value.num()!! }
            bad.check("$label $name spans", wantMap.keys == g.keys) { "pc=${wantMap.keys} kotlin=${g.keys}" }
            for ((k, v) in wantMap) bad.check("$label $name $k", close(v, g[k], 1e-9, 1e-9)) { "pc=$v kotlin=${g[k]}" }
        }
        side("power", want.obj("power"), got.power) { it.toInt() }
        side("speed", want.obj("speed"), got.speed) { it.toInt() }
        side("distance_time", want.obj("distance_time"), got.distanceTime) { it.toDouble() }
    }

    @Test
    fun singleFilesMatchThePcApp() {
        if (!Golden.available) { println("golden fixtures missing: skipped"); return }
        val s = thresholds()
        val bad = Mismatches()
        var power = 0
        for (entry in Golden.perFile) {
            val name = entry["input"].str()!!
            val a = Importer.parse(name, Golden.rawBytes(name))
            val want = entry.obj("metrics")
            val m = TrainingLoad.streamMetrics(a.streams)
            bad.check("$name NP", close(want["normalized_power"].num(), m.normalizedPower, 1e-9, 1e-9)) { "pc=${want["normalized_power"].num()} kotlin=${m.normalizedPower}" }
            bad.check("$name power secs", close(want["power_seconds"].num(), m.powerSeconds)) { "pc=${want["power_seconds"].num()} kotlin=${m.powerSeconds}" }
            bad.check("$name best20", close(want["best_20min_power"].num(), m.best20minPower, 1e-9, 1e-9)) { "pc=${want["best_20min_power"].num()} kotlin=${m.best20minPower}" }
            bad.check("$name hr_max", close(want["hr_max"].num(), m.hrMax, 1e-9, 1e-9)) { "pc=${want["hr_max"].num()} kotlin=${m.hrMax}" }
            bad.check("$name hr_hist", histOf(want["hr_hist"].str()) == m.hrHist) { "differs" }

            compareBests(name, entry.obj("bests"), Curves.bestEfforts(a.sport, a.streams), bad)

            val wantLoad = entry.obj("load")
            val load = TrainingLoad.activityLoad(a.sport, a.duration, m, s)
            if (load.method == LoadMethod.POWER) power++
            bad.check("$name load", close(wantLoad["load"].num(), load.load, 1e-9, 1e-9)) { "pc=${wantLoad["load"].num()} kotlin=${load.load}" }
            bad.check("$name method", wantLoad["method"].str() == load.method.label) { "pc=${wantLoad["method"].str()} kotlin=${load.method.label}" }
        }
        println("single files: ${Golden.perFile.size}, $power scored by power; mismatches: ${bad.items.size}")
        bad.items.take(60).forEach { println("  MISMATCH $it") }
        assertTrue(bad.items.isEmpty(), "${bad.items.size} mismatches, first: ${bad.items.firstOrNull()}")
    }

    @Test
    fun resamplingFillsShortGapsAndDropsPauses() {
        // 0,1,2 then a 4 s gap (filled), then a 9 s gap (a pause: only the first 5 are carried forward), the rest dropped.
        val t = doubleArrayOf(0.0, 1.0, 2.0, 7.0, 17.0)
        val v = doubleArrayOf(10.0, 11.0, 12.0, 13.0, 14.0)
        // 3..6 are NaN after sample 2 (12): 4 filled; then 8..16 NaN after 13: 5 filled, 4 dropped; 17 present.
        assertEquals(listOf(10.0, 11.0, 12.0, 12.0, 12.0, 12.0, 12.0, 13.0, 13.0, 13.0, 13.0, 13.0, 13.0, 14.0), Resample.oneHz(t, v).toList())
        // Distance is interpolated across the same gaps instead of held flat.
        val d = Resample.oneHz(doubleArrayOf(0.0, 4.0), doubleArrayOf(0.0, 8.0), interpolate = true)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), d.toList())
    }

    @Test
    fun wholeHistoryMatchesThePcApp() = runBlocking<Unit> {
        val seed = File(System.getProperty("seed.dir") ?: return@runBlocking)
        val dir = File(seed, "imports")
        if (!Golden.available || !dir.isDirectory) { println("seed/imports or golden missing: skipped"); return@runBlocking }
        val want = Golden.root.obj("full_history")
        val today = LocalDate.parse(want["today"].str()!!)

        class Row(val hash: String, val sport: String, val date: LocalDate, val duration: Double?, val metrics: StreamMetrics, val bests: Bests)

        fun activityId(name: String): Long =
            Regex("^[0-9]+").find(name.replace(Regex("^[0-9a-f]{12}_"), ""))?.value?.toLongOrNull() ?: -1
        val order = dir.listFiles()!!.sortedWith(compareBy({ activityId(it.name) },{ it.name.replace(Regex("^[0-9a-f]{12}_"), "") }))

        val pcSport = File(seed, "pc-activities.json").takeIf { it.exists() }?.let { f ->
            Json.parseToJsonElement(f.readText()).let { (it as kotlinx.serialization.json.JsonArray) }.map { it.jsonObject }
                .associate { it["file_hash"].str()!! to it["sport"].str()!! }
        } ?: emptyMap()
        val rows = LinkedHashMap<String, Row>()
        for (f in order) {
            val d = try { ImportPlanner.plan(f.name, f.readBytes(), none, UntypedSport.GUESS_FOOT, MatchMode.OFF) } catch (_: Exception) { continue }
            if (d !is ImportDecision.Import || d.fileHash in rows) continue
            val a = d.activity
            val sport = pcSport[d.fileHash] ?: d.sport
            rows[d.fileHash] = Row(d.fileHash, sport, TrainingLoad.localDate(a.startTime), a.duration,
                TrainingLoad.streamMetrics(a.streams), Curves.bestEfforts(sport, a.streams))
        }
        println("replayed ${order.size} files into ${rows.size} activities")

        val bad = Mismatches()
        // Thresholds: estimated from the data when the user set none.
        val maxHrSeen = rows.values.mapNotNull { it.metrics.hrMax }.maxOrNull()
        val best20 = rows.values.filter { it.sport in TrainingLoad.POWER_SPORTS }.mapNotNull { it.metrics.best20minPower }.maxOrNull()
        val settings = TrainingLoad.effectiveSettings(emptyMap(), maxHrSeen, best20)
        val ws = want.obj("settings")
        for ((key, got) in listOf("max_hr" to settings.maxHr, "resting_hr" to settings.restingHr, "threshold_hr" to settings.thresholdHr, "ftp" to settings.ftp)) {
            val w = ws.obj(key)
            bad.check("setting $key", close(w["value"].num(), got.value) && w["source"].str() == got.source?.name?.lowercase()) {
                "pc=${w["value"].num()}/${w["source"].str()} kotlin=${got.value}/${got.source}" }
        }

        // Per-activity load.
        val wantMetrics = want.obj("activity_metrics")
        val perDay = HashMap<LocalDate, Double>()
        var methodMismatch = 0
        for (r in rows.values) {
            val load = TrainingLoad.activityLoad(r.sport, r.duration, r.metrics, settings.values)
            perDay.merge(r.date, load.load, Double::plus)
            val w = wantMetrics[r.hash]?.jsonObject
            if (w == null) { bad.items += "${r.hash.take(12)} not in the PC metrics"; continue }
            if (w["load_method"].str() != load.method.label) methodMismatch++
            bad.check("${r.hash.take(12)} load", close(w["load"].num(), load.load, 1e-9, 1e-9)) { "pc=${w["load"].num()} kotlin=${load.load}" }
            bad.check("${r.hash.take(12)} hr_max", close(w["hr_max"].num(), r.metrics.hrMax, 1e-9, 1e-9)) { "pc=${w["hr_max"].num()} kotlin=${r.metrics.hrMax}" }
            bad.check("${r.hash.take(12)} NP", close(w["normalized_power"].num(), r.metrics.normalizedPower, 1e-9, 1e-9)) { "pc=${w["normalized_power"].num()} kotlin=${r.metrics.normalizedPower}" }
        }
        bad.check("activity count", wantMetrics.size == rows.size) { "pc=${wantMetrics.size} kotlin=${rows.size}" }
        bad.check("load methods", methodMismatch == 0) { "$methodMismatch differ" }

        // CTL / ATL / TSB.
        val series = TrainingLoad.dailySeries(perDay, today)
        val wantDays = want["daily_load"]!!.let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonObject } }
        bad.check("daily rows", wantDays.size == series.size) { "pc=${wantDays.size} kotlin=${series.size}" }
        for ((w, g) in wantDays.zip(series)) {
            val day = w["date"].str()
            bad.check("day $day date", day == g.date.toString()) { "kotlin=${g.date}" }
            for ((k, v) in listOf("load" to g.load, "ctl" to g.ctl, "atl" to g.atl, "tsb" to g.tsb))
                bad.check("day $day $k", close(w[k].num(), v, 1e-8, 1e-8)) { "pc=${w[k].num()} kotlin=$v" }
        }

        // Curves, by sport.
        val wantCurves = want.obj("curves")
        for (sport in rows.values.map { it.sport }.toSet()) {
            val got = Curves.aggregate(rows.values.filter { it.sport == sport }.map { Curves.Entry(it.hash, it.date, it.bests) })
            val w = wantCurves[sport]?.jsonObject
            val gotAny = got.values.any { it.isNotEmpty() }
            if (w == null) { bad.check("curves $sport unexpected", !gotAny) { "kotlin has curves, PC has none" }; continue }
            for ((metric, key) in listOf(Curves.Metric.POWER to "power", Curves.Metric.SPEED to "speed", Curves.Metric.DISTANCE_TIME to "distance_time")) {
                val wantPoints = (w[key] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
                val gotPoints = got.getValue(metric)
                bad.check("curve $sport $key points", wantPoints.size == gotPoints.size) { "pc=${wantPoints.size} kotlin=${gotPoints.size}" }
                for ((wp, gp) in wantPoints.zip(gotPoints)) {
                    bad.check("curve $sport $key ${wp["span"].num()}", close(wp["span"].num(), gp.span) && close(wp["value"].num(), gp.value, 1e-9, 1e-9) &&
                        wp["date"].str() == gp.date.toString()) { "pc=${wp["value"].num()}@${wp["date"].str()} kotlin=${gp.value}@${gp.date}" }
                }
            }
        }
        println("days ${series.size}; mismatches: ${bad.items.size}")
        bad.items.take(60).forEach { println("  MISMATCH $it") }
        assertTrue(bad.items.isEmpty(), "${bad.items.size} mismatches, first: ${bad.items.firstOrNull()}")
    }
}
