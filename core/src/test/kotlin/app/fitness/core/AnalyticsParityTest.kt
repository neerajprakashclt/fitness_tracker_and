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
            val m = TrainingLoad.streamMetrics(a.streams, a.sport)
            bad.check("$name NP", close(want["normalized_power"].num(), m.normalizedPower, 1e-9, 1e-9)) { "pc=${want["normalized_power"].num()} kotlin=${m.normalizedPower}" }
            bad.check("$name power secs", close(want["power_seconds"].num(), m.powerSeconds)) { "pc=${want["power_seconds"].num()} kotlin=${m.powerSeconds}" }
            bad.check("$name best20", close(want["best_20min_power"].num(), m.best20minPower, 1e-9, 1e-9)) { "pc=${want["best_20min_power"].num()} kotlin=${m.best20minPower}" }
            bad.check("$name hr_max", close(want["hr_max"].num(), m.hrMax, 1e-9, 1e-9)) { "pc=${want["hr_max"].num()} kotlin=${m.hrMax}" }
            bad.check("$name hr_hist", histOf(want["hr_hist"].str()) == m.hrHist) { "differs" }

            bad.check("$name speed_hist", histOf(want["speed_hist"].str()) == m.speedHist) { "differs" }
            bad.check("$name efficiency", close(want["efficiency"].num(), m.efficiency, 1e-9, 1e-9) && want["efficiency_basis"].str() == m.efficiencyBasis &&
                close(want["decoupling"].num(), m.decoupling, 1e-9, 1e-9)) { "pc=${want["efficiency"].num()}/${want["decoupling"].num()} kotlin=${m.efficiency}/${m.decoupling}" }
            val wantReps = want["reps"].str()?.let { (Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonArray).map { r -> r.jsonObject } } ?: emptyList()
            val gotReps = m.reps ?: emptyList()
            bad.check("$name reps", wantReps.size == gotReps.size && wantReps.zip(gotReps).all { (w, g) ->
                w["start"].num()!!.toInt() == g.start && w["duration"].num()!!.toInt() == g.duration && close(w["speed"].num(), g.speed, 1e-9, 1e-9) &&
                    w["recovery"].num()?.toInt() == g.recovery && close(w["recovery_speed"].num(), g.recoverySpeed, 1e-9, 1e-9) }) { "pc=${wantReps.size} kotlin=${gotReps.size}" }
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

        class Row(val hash: String, val sport: String, val start: java.time.Instant, val date: LocalDate, val distance: Double?, val duration: Double?, val elevation: Double?, val metrics: StreamMetrics, val bests: Bests, val polylines: List<String>)

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
            rows[d.fileHash] = Row(d.fileHash, sport, a.startTime, TrainingLoad.localDate(a.startTime), a.distance, a.duration, a.elevationGain,
                TrainingLoad.streamMetrics(a.streams, sport), Curves.bestEfforts(sport, a.streams),
                Routes.segments(a.streams))
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
        val loads = HashMap<String, Double>()
        var methodMismatch = 0
        for (r in rows.values) {
            val load = TrainingLoad.activityLoad(r.sport, r.duration, r.metrics, settings.values)
            perDay.merge(r.date, load.load, Double::plus)
            loads[r.hash] = load.load
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
        // Critical speed today, and every run's type (the poster's colours).
        val runningRows = rows.values.filter { it.sport == "running" }.sortedBy { it.start }
        val efforts = CriticalSpeed.efforts(runningRows.map { Curves.Entry(it.hash, it.date, it.bests) })
        val cs = CriticalSpeed.fitWindow(efforts, today)
        val wantCs = want.obj("critical_speed").obj("current")
        bad.check("critical speed", cs != null && close(wantCs["cs"].num(), cs.cs, 1e-9, 1e-9) && close(wantCs["d_prime"].num(), cs.dPrime, 1e-9, 1e-9) &&
            wantCs["points"].num()!!.toInt() == cs.points && wantCs["window_days"].num()!!.toInt() == cs.windowDays && close(wantCs["r2"].num(), cs.r2, 1e-9, 1e-9) &&
            wantCs["note"].str() == cs.note) { "pc=$wantCs kotlin=${cs?.cs}/${cs?.dPrime}" }
        val inputs = runningRows.map { r ->
            RunInput(r.hash, r.date, r.distance, r.duration, r.metrics.speedHist, r.metrics.hrHist, r.metrics.reps)
        }
        val classified = RunTypes.classifyAll(inputs, efforts, settings.maxHr.value)
        // Runs recorded at the very same moment have no defined order, so compare in (date, distance, duration) order.
        val wantTypes = (want["run_types"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            .sortedWith(compareBy({ it["date"].str() }, { it["distance"].num() }, { it["duration"].num() }))
        val gotSorted = classified.withIndex().sortedWith(compareBy({ inputs[it.index].date }, { inputs[it.index].distance }, { inputs[it.index].duration })).map { it.value }
        bad.check("run type count", wantTypes.size == classified.size) { "pc=${wantTypes.size} kotlin=${classified.size}" }
        // Runs on the same day can be recorded at the very same moment, and then which one the other sees as context is arbitrary.
        val twins = wantTypes.groupingBy { it["date"].str() }.eachCount()
        val typeCounts = sortedMapOf<String, Int>()
        for ((i, w) in wantTypes.withIndex()) {
            val g = gotSorted.getOrNull(i)?.first ?: break
            typeCounts.merge(g.type.label, 1, Int::plus)
            val d = w["detail"]!!.jsonObject
            val tied = twins.getValue(w["date"].str()) > 1
            if (g.type == RunType.INTERVALS && !tied) bad.check("run ${w["date"].str()} #$i reps", d["reps"].num()!!.toInt() == g.reps && close(d["median_seconds"].num(), g.repMedianSeconds) &&
                close(d["speed"].num(), g.repSpeed, 1e-9, 1e-9)) { "pc=$d kotlin=${g.reps}/${g.repMedianSeconds}/${g.repSpeed}" }
            if (g.type == RunType.LONG && !tied) bad.check("run ${w["date"].str()} #$i long", close(d["distance"].num(), g.distance) && close(d["duration"].num(), g.duration) &&
                close(d["typical_distance"].num(), g.typicalDistance, 1e-9, 1e-6)) { "pc=$d kotlin=${g.distance}/${g.duration}/${g.typicalDistance}" }
            bad.check("run ${w["date"].str()} #$i type", w["type"].str() == g.type.label && w["basis"].str() == g.basis &&
                close(d["easy_share"].num(), g.easyShare, 1e-9, 1e-9) && close(d["hard_share"].num(), g.hardShare, 1e-9, 1e-9)) {
                "pc=${w["type"].str()}/${w["basis"].str()}/$d kotlin=${g.type.label}/${g.basis}/${g.easyShare}" }
        }
        println("run types: $typeCounts")

        // Same-route groups.
        val withRoute = runningRows.filter { it.polylines.isNotEmpty() }
        val groups = RouteMatch.group(RouteMatch.signatures(withRoute.associate { it.hash to RouteMatch.track(it.polylines) }), withRoute.map { it.hash })
            .sortedByDescending { it.size }
        // The fixture was exported with at least 2 runs per route.
        val repeated = groups.filter { it.size >= 2 }
        val wantRoutes = want.obj("routes")
        val wantList = (wantRoutes["routes"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        bad.check("route total", wantRoutes["total_routes"].num()!!.toInt() == repeated.size) { "pc=${wantRoutes["total_routes"].num()} kotlin=${repeated.size}" }
        val byHash = withRoute.associateBy { it.hash }
        for ((i, w) in wantList.withIndex()) {
            val g = repeated.getOrNull(i) ?: break
            val first = byHash.getValue(g[0])
            val last = byHash.getValue(g.last())
            bad.check("route #$i", w["runs_count"].num()!!.toInt() == g.size && w["first_date"].str() == first.date.toString() && w["last_date"].str() == last.date.toString() &&
                (w["polyline"] as kotlinx.serialization.json.JsonArray).map { it.str() } == first.polylines) {
                "pc=${w["runs_count"].num()} ${w["first_date"].str()}..${w["last_date"].str()} kotlin=${g.size} ${first.date}..${last.date}" }
        }
        bad.check("runs in routes", wantRoutes["runs_in_routes"].num()!!.toInt() == repeated.sumOf { it.size }) { "pc=${wantRoutes["runs_in_routes"].num()} kotlin=${repeated.sumOf { it.size }}" }
        println("route groups: ${groups.size}, repeated ${repeated.size}")

        // Cities: the places each route passes, and the areas they group into.
        val placesFile = File(seed, "pc-places.json")
        if (placesFile.exists()) {
            val oracle = Json.parseToJsonElement(placesFile.readText()).jsonObject
            val per = oracle.obj("per_activity")
            val visits = ArrayList<Pair<String, List<Place>>>()
            var compared = 0
            for (r in runningRows) {
                if (r.polylines.isEmpty()) continue
                val got = Places.visited(r.polylines)
                visits += r.start.toString().take(4) to got
                val w = per[r.hash] as? kotlinx.serialization.json.JsonArray ?: continue
                compared++
                val want = w.map { (it as kotlinx.serialization.json.JsonArray) }
                bad.check("places ${r.hash.take(12)}", want.map { listOf(it[0].str(), it[1].str(), it[2].str()) } == got.map { listOf(it.name, it.region, it.country) }) {
                    "pc=${want.map { it[0].str() }} kotlin=${got.map { it.name }}" }
            }
            // Cities with the same number of runs have no defined order (the PC's depends on its row order), so sort by name too.
            val areas = Places.areas(visits.filter { it.second.isNotEmpty() }).sortedWith(compareBy({ -it.runsByYear.values.sum() }, { it.name }))
            val wantAreas = (oracle.obj("areas")["areas"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
                .sortedWith(compareBy({ -it.obj("runs_by_year").values.sumOf { v -> v.num()!! } }, { it["name"].str() }))
            bad.check("area count", wantAreas.size == areas.size) { "pc=${wantAreas.size} kotlin=${areas.size}" }
            for ((w, g) in wantAreas.zip(areas)) {
                val years = w.obj("runs_by_year").entries.associate { it.key to it.value.num()!!.toInt() }
                bad.check("area ${w["name"].str()}", w["name"].str() == g.name && w["country"].str() == g.country && years == g.runsByYear &&
                    (w["places"] as kotlinx.serialization.json.JsonArray).map { it.str() }.toSet() == g.places.toSet()) { "pc=${w["name"].str()}/$years kotlin=${g.name}/${g.runsByYear}" }
            }
            println("places: $compared routes compared, ${areas.size} areas (${areas.take(3).map { it.name }})")
        }

        // The running screens: efficiency, predictions, critical-speed analysis, weekly mix, route progress.
        val effRows = rows.values.filter { it.metrics.efficiency != null }.sortedBy { it.start }.map {
            Efficiency.Row(it.hash, it.sport, it.date, it.duration, it.distance, it.metrics.efficiency!!, it.metrics.efficiencyBasis, it.metrics.decoupling) }
        val wantEff = want.obj("efficiency")
        for ((sport, w) in wantEff) {
            val wj = w.jsonObject
            val g0 = Efficiency.series(effRows, sport)
            val g = EfficiencySeries(g0.sports, g0.sport, g0.basis, g0.points.sortedWith(compareBy({ it.date }, { Math.rint(it.efficiency * 1e6) })), g0.trendPctPerMonth)
            val wp = (wj["points"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
                .sortedWith(compareBy({ it["date"].str() }, { Math.rint(it["efficiency"].num()!! * 1e6) }))
            bad.check("efficiency $sport shape", wj["basis"].str() == g.basis && wp.size == g.points.size && wj["sports"]!!.let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.str() }.toSet() } == g.sports.toSet()) {
                "pc=${wj["basis"].str()}/${wp.size} kotlin=${g.basis}/${g.points.size}" }
            bad.check("efficiency $sport trend", close(wj["trend_pct_per_month"].num(), g.trendPctPerMonth, 1e-7, 1e-9)) { "pc=${wj["trend_pct_per_month"].num()} kotlin=${g.trendPctPerMonth}" }
            for ((a, b) in wp.zip(g.points)) bad.check("efficiency $sport ${a["date"].str()}", a["date"].str() == b.date.toString() && close(a["efficiency"].num(), b.efficiency, 1e-9, 1e-9) &&
                close(a["decoupling"].num(), b.decoupling, 1e-9, 1e-9)) { "pc=${a["efficiency"].num()} kotlin=${b.efficiency}" }
        }

        val since = today.minusDays(RacePredictions.WINDOW_DAYS.toLong())
        val recent = Curves.aggregate(runningRows.map { Curves.Entry(it.hash, it.date, it.bests) }, since).getValue(Curves.Metric.DISTANCE_TIME)
        val pred = RacePredictions.predict(recent)
        val wantPred = want.obj("predictions")
        bad.check("predictions available", wantPred["available"].toString() == (pred != null).toString()) { "pc=${wantPred["available"]} kotlin=${pred != null}" }
        if (pred != null) {
            val wp = (wantPred["predictions"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            for ((a, b) in wp.zip(pred.predictions)) bad.check("prediction ${b.name}", close(a["seconds"].num(), b.seconds, 1e-9, 1e-9) && a["exact"].toString() == b.exact.toString() &&
                close(a.obj("from_effort")["seconds"].num(), b.from.value) && a.obj("from_effort")["date"].str() == b.from.date.toString()) { "pc=${a["seconds"].num()} kotlin=${b.seconds}" }
            val vo = wantPred["vo2max"]!!.jsonObject
            bad.check("vdot", close(vo["vdot"].num(), pred.vdot, 1e-9, 1e-9) && vo.obj("from_effort")["date"].str() == pred.vdotFrom?.date.toString()) { "pc=${vo["vdot"].num()} kotlin=${pred.vdot}" }
        }

        val wantCsA = want.obj("critical_speed")
        if (cs != null) {
            val wz = (wantCsA["zones"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            for ((a, b) in wz.zip(CriticalSpeedReport.zoneBounds(cs.cs))) bad.check("cs zone ${b.name}", a["name"].str() == b.name && close(a["low"].num(), b.low, 1e-9, 1e-9) && close(a["high"].num(), b.high, 1e-9, 1e-9)) { "differs" }
            val wt = (wantCsA["trend"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            val gt = CriticalSpeedReport.monthlyTrend(efforts, today)
            bad.check("cs trend length", wt.size == gt.size) { "pc=${wt.size} kotlin=${gt.size}" }
            for ((a, b) in wt.zip(gt)) bad.check("cs trend ${a["date"].str()}", a["date"].str() == b.date.toString() && close(a["cs"].num(), b.cs, 1e-9, 1e-9) && close(a["d_prime"].num(), b.dPrime, 1e-9, 1e-9)) { "pc=${a["cs"].num()} kotlin=${b.cs}" }
            val wpr = (wantCsA["predictions"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            val gpr = CriticalSpeedReport.predict(cs.cs, cs.dPrime)
            bad.check("cs predictions", wpr.size == gpr.size && wpr.zip(gpr).all { (a, b) -> a["name"].str() == b.name && close(a["seconds"].num(), b.seconds, 1e-9, 1e-9) }) { "differs" }
        }

        val mix = RunTypeMix.weekly(classified.mapIndexed { i, c -> Triple(inputs[i].date, inputs[i].distance, c.first.type) }, today)
        val wantMix = (want.obj("run_types_weekly")["weeks"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        bad.check("mix weeks", wantMix.size == mix.size) { "pc=${wantMix.size} kotlin=${mix.size}" }
        for ((a, b) in wantMix.zip(mix)) {
            val wc = a.obj("counts").entries.associate { it.key to it.value.num()!!.toInt() }
            val wk = a.obj("km").entries.associate { it.key to it.value.num()!! }
            bad.check("mix ${a["week_start"].str()}", a["week_start"].str() == b.weekStart.toString() && wc == b.counts.entries.associate { it.key.label to it.value } &&
                wk.keys == b.km.keys.map { it.label }.toSet() && wk.all { (k, v) -> close(v, b.km.entries.first { e -> e.key.label == k }.value, 1e-9, 1e-9) }) { "pc=$wc kotlin=${b.counts}" }
        }

        val progress = RouteProgress.routes(withRoute.map {
            RouteProgress.Input(it.hash, it.date, it.distance, it.duration, it.metrics.hrHist, it.metrics.efficiency, it.polylines) }, 2, 50)
        for ((i, a) in wantList.withIndex()) {
            val b = progress.getOrNull(i) ?: break
            val ar = (a["runs"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
                .sortedWith(compareBy({ it["date"].str() }, { Math.rint((it["efficiency"].num() ?: 0.0) * 1e6) }, { Math.rint((it["avg_hr"].num() ?: 0.0) * 1e6) }))
            val br = b.runs.sortedWith(compareBy({ it.date.toString() }, { Math.rint((it.efficiency ?: 0.0) * 1e6) }, { Math.rint((it.avgHr ?: 0.0) * 1e6) }))
            bad.check("route report #$i", a["runs_count"].num()!!.toInt() == b.runs.size && close(a["distance"].num(), b.distance, 1e-9, 1e-6) && close(a["median_speed"].num(), b.medianSpeed, 1e-9, 1e-9) &&
                a.obj("best")["date"].str() == b.best?.date.toString() &&
                close(a.obj("trend")["speed_pct_per_year"].num(), b.trend.speedPctPerYear, 1e-6, 1e-9) && close(a.obj("trend")["hr_bpm_per_year"].num(), b.trend.hrBpmPerYear, 1e-6, 1e-9) &&
                ar.zip(br).all { (x, y) -> x["date"].str() == y.date.toString() && close(x["avg_hr"].num(), y.avgHr, 1e-9, 1e-9) && close(x["efficiency"].num(), y.efficiency, 1e-9, 1e-9) }) {
                "pc=${a["runs_count"].num()} ${a.obj("trend")} kotlin=${b.runs.size} ${b.trend.speedPctPerYear}/${b.trend.hrBpmPerYear}" }
        }

        // Training log, year over year and weekly heart-rate zones, with the climb Strava's CSV and terrain data add to the files.
        val sideGain = Json.parseToJsonElement(File(seed, "pc-side-data.json").readText()).jsonObject.values
            .flatMap { (it as kotlinx.serialization.json.JsonArray) }.associate { it.jsonObject["file_hash"].str()!! to it.jsonObject["elevation_gain"].num()!! }
        val logRows = rows.values.sortedBy { it.start }.map {
            val gain = it.elevation ?: sideGain[it.hash]
            LogActivity(it.hash, it.sport, it.date, it.duration, it.distance, gain, loads[it.hash], gain == null && it.polylines.isNotEmpty())
        }
        for ((period, key) in listOf(Period.WEEK to "week", Period.MONTH to "month", Period.YEAR to "year")) {
            val wantRows = (want.obj("log")[key] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            val got = TrainingLog.totals(logRows, period)
            bad.check("log $key rows", wantRows.size == got.size) { "pc=${wantRows.size} kotlin=${got.size}" }
            for ((a, b) in wantRows.zip(got)) bad.check("log $key ${a["start"].str()}", a["start"].str() == b.start.toString() && a["activities"].num()!!.toInt() == b.activities &&
                close(a["distance"].num(), b.distance, 1e-9, 1e-6) && close(a["duration"].num(), b.duration, 1e-9, 1e-6) && close(a["load"].num(), b.load, 1e-9, 1e-6) &&
                close(a["elevation"].num(), b.elevation, 1e-9, 1e-6) && a["elevation_missing"].num()!!.toInt() == b.elevationMissing) {
                "pc=$a kotlin=${b.start}/${b.activities}/${b.distance}/${b.load}/${b.elevation}/${b.elevationMissing}" }
        }
        for ((metric, key) in listOf(LogMetric.DISTANCE to "distance", LogMetric.DURATION to "duration", LogMetric.ELEVATION to "elevation", LogMetric.LOAD to "load")) {
            val wantYears = want.obj("log_yearly").obj(key).obj("years")
            val got = TrainingLog.yearOverYear(logRows, metric, today)
            bad.check("yearly $key years", wantYears.keys == got.years.keys.map { it.toString() }.toSet()) { "differs" }
            for ((year, months) in got.years) {
                val w = (wantYears[year.toString()] as kotlinx.serialization.json.JsonArray).map { it.num() }
                bad.check("yearly $key $year", w.size == months.size && w.zip(months).all { (a, b) -> close(a, b, 1e-9, 1e-6) }) { "pc=$w kotlin=$months" }
            }
        }
        val hrRows = rows.values.sortedBy { it.start }.mapNotNull { r -> r.metrics.hrHist?.let { Triple(r.sport, r.date, it) } }
        val zw = WeeklyZoneReport.weekly(hrRows, settings.maxHr.value!!)
        val wantZ = want.obj("zones")
        if (zw == null) bad.items += "no weekly zones" else {
            val wz = (wantZ["weeks"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            bad.check("zone weeks", wz.size == zw.weeks.size) { "pc=${wz.size} kotlin=${zw.weeks.size}" }
            for ((a, b) in wz.zip(zw.weeks)) bad.check("zones ${a["week_start"].str()}", a["week_start"].str() == b.weekStart.toString() &&
                (a["seconds"] as kotlinx.serialization.json.JsonArray).map { it.num()!! }.zip(b.seconds.toList()).all { (x, y) -> close(x, y, 1e-9, 1e-6) }) { "differs" }
            val wt = wantZ.obj("totals")
            val pol = wt.obj("polarization")
            bad.check("zone totals", (wt["seconds"] as kotlinx.serialization.json.JsonArray).map { it.num()!! }.zip(zw.totals.toList()).all { (x, y) -> close(x, y, 1e-9, 1e-5) } &&
                close(pol["easy"].num(), zw.polarization?.easy, 1e-9, 1e-9) && close(pol["hard"].num(), zw.polarization?.hard, 1e-9, 1e-9) && pol["label"].str() == zw.polarization?.label) {
                "pc=$pol kotlin=${zw.polarization?.label}" }
        }

        // Personal-best timeline and weekly streak (the poster's inputs).
        val running = rows.values.filter { it.sport == "running" }.sortedBy { it.start }
        val timeline = Volume.recordsTimeline(running.map { Curves.Entry(it.hash, it.date, it.bests) })
        val wantTimeline = (want.obj("records_timeline")["distances"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        bad.check("record distances", wantTimeline.size == timeline.size) { "pc=${wantTimeline.size} kotlin=${timeline.size}" }
        for ((w, g) in wantTimeline.zip(timeline)) {
            val wr = (w["records"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            bad.check("records ${g.name}", wr.size == g.records.size && wr.zip(g.records).all { (a, b) -> a["date"].str() == b.date.toString() && close(a["seconds"].num(), b.seconds) }) {
                "pc=${wr.map { it["date"].str() to it["seconds"].num() }} kotlin=${g.records.map { it.date to it.seconds }}" }
        }
        bad.check("longest streak", want.obj("consistency")["longest_streak_weeks"].num()!!.toInt() == Volume.longestStreakWeeks(running.map { it.date })) {
            "pc=${want.obj("consistency")["longest_streak_weeks"].num()} kotlin=${Volume.longestStreakWeeks(running.map { it.date })}" }

        println("days ${series.size}; mismatches: ${bad.items.size}")
        bad.items.take(60).forEach { println("  MISMATCH $it") }
        assertTrue(bad.items.isEmpty(), "${bad.items.size} mismatches, first: ${bad.items.firstOrNull()}")
    }
}
