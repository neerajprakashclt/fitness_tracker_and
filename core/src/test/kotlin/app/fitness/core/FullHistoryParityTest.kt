package app.fitness.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end: replay the whole staged archive through the import pipeline in the PC's order (Strava activity id, oldest
 * first) and compare the result with the PC's own database (`seed/pc-activities.json`, exported read-only from it): the
 * same set of activities, and for each one the same sport, start, moving time, distance, device and lap count. Elevation
 * is compared except where the PC took it from outside the file (Strava's CSV or terrain data), a phase 7 feature.
 *
 * The comparison runs with same-run detection off because every file in the archive was deliberately imported on the PC,
 * some before it had that rule. A second check shows what the default rule does to this history: it skips exactly two
 * files the PC kept (a swim recorded by two apps, and two real walks 13 hours apart that look like a time-zone slip).
 * The three old Samsung Health files the PC left as "unknown" are labelled walking by the run/walk guess; that is allowed.
 */
class FullHistoryParityTest {
    private class Stored(val hash: String, val sport: String, val start: Instant, val duration: Double?, val distance: Double?,
                         val elevation: Double?, val device: String?, val laps: Int)

    private class MemoryStore : ActivityLookup {
        val rows = LinkedHashMap<String, Stored>()
        override suspend fun exists(fileHash: String) = fileHash in rows
        override suspend fun candidates(sport: String, anySport: Boolean, fromDate: String, toDate: String): List<SimilarCandidate> =
            rows.values.filter { (anySport || it.sport == sport) && (it.distance ?: 0.0) > 0 && it.start.toString().substring(0, 10) in fromDate..toDate }
                .map { SimilarCandidate(it.hash, it.start, it.distance!!) }
    }

    /** The PC walks a folder in order of the leading number in each file name; archive names add a hash prefix to strip. */
    private fun activityId(name: String): Long =
        Regex("^[0-9]+").find(name.replace(Regex("^[0-9a-f]{12}_"), ""))?.value?.toLongOrNull() ?: -1

    private suspend fun replay(order: List<File>, mode: MatchMode, store: MemoryStore, sameRun: MutableList<String>, failed: MutableList<String>): Int {
        var duplicates = 0
        for (f in order) {
            try {
                when (val d = ImportPlanner.plan(f.name, f.readBytes(), store, UntypedSport.GUESS_FOOT, mode)) {
                    is ImportDecision.Duplicate -> duplicates++
                    is ImportDecision.SameRun -> sameRun += "${f.name.take(12)} same run as ${d.match.fileHash.take(12)} (${d.match.kind}, ${d.match.offsetSeconds}s)"
                    is ImportDecision.Import -> store.rows[d.fileHash] = Stored(d.fileHash, d.sport, d.activity.startTime, d.activity.duration,
                        d.activity.distance, d.activity.elevationGain, d.activity.device, d.activity.laps.size)
                }
            } catch (e: Exception) { failed += "${f.name}: ${e.message}" }
        }
        return duplicates
    }

    @Test
    fun wholeHistoryMatchesThePcDatabase() = runBlocking<Unit> {
        val seed = File(System.getProperty("seed.dir") ?: return@runBlocking)
        val dir = File(seed, "imports")
        val oracleFile = File(seed, "pc-activities.json")
        if (!dir.isDirectory || !oracleFile.exists()) { println("seed/imports or pc-activities.json missing: skipped"); return@runBlocking }

        val external = Json.parseToJsonElement(File(seed, "pc-side-data.json").readText()).jsonObject.values
            .flatMap { it.jsonArray }.map { it.jsonObject["file_hash"]!!.toString().trim('"') }.toSet()
        val oracle = Json.parseToJsonElement(oracleFile.readText()).jsonArray.map { it.jsonObject }
            .associateBy { it["file_hash"]!!.toString().trim('"') }
        val order = dir.listFiles()!!.sortedWith(compareBy({ activityId(it.name) }, { it.name.replace(Regex("^[0-9a-f]{12}_"), "") }))

        val store = MemoryStore()
        val failed = mutableListOf<String>()
        val duplicates = replay(order, MatchMode.OFF, store, mutableListOf(), failed)
        println("replayed ${order.size} files: ${store.rows.size} activities, $duplicates identical, ${failed.size} failed; PC has ${oracle.size}")
        failed.take(10).forEach { println("  FAILED $it") }

        val bad = Mismatches()
        var guessed = 0
        for (h in oracle.keys - store.rows.keys) bad.items += "missing from Kotlin: $h ${oracle.getValue(h)["source_file"]}"
        for (h in store.rows.keys - oracle.keys) bad.items += "extra in Kotlin: $h"
        for ((h, o) in oracle) {
            val k = store.rows[h] ?: continue
            val pcSport = o["sport"].str()
            if (pcSport == "unknown" && k.sport != "unknown") guessed++
            else bad.check("$h sport", pcSport == k.sport) { "pc=$pcSport kotlin=${k.sport}" }
            bad.check("$h start", Times.parse(o["start_time"].str()) == k.start) { "pc=${o["start_time"].str()} kotlin=${k.start}" }
            bad.check("$h duration", close(o["duration"].num(), k.duration, 1e-9, 1e-6)) { "pc=${o["duration"].num()} kotlin=${k.duration}" }
            bad.check("$h distance", close(o["distance"].num(), k.distance, 1e-9, 1e-6)) { "pc=${o["distance"].num()} kotlin=${k.distance}" }
            bad.check("$h device", o["device"].str() == k.device) { "pc=${o["device"].str()} kotlin=${k.device}" }
            bad.check("$h laps", o["laps"].num()!!.toInt() == k.laps) { "pc=${o["laps"].num()} kotlin=${k.laps}" }
            if (h !in external) bad.check("$h elevation", close(o["elevation_gain"].num(), k.elevation, 1e-9, 1e-6)) { "pc=${o["elevation_gain"].num()} kotlin=${k.elevation}" }
        }
        println("mismatches: ${bad.items.size}; PC 'unknown' now labelled by the run/walk guess: $guessed")
        bad.items.take(40).forEach { println("  MISMATCH $it") }
        assertEquals(oracle.size, store.rows.size)
        assertTrue(bad.items.isEmpty(), "${bad.items.size} mismatches, first: ${bad.items.firstOrNull()}")

        // The default rule on the same history: exactly the two documented files are skipped.
        val withRule = MemoryStore()
        val skipped = mutableListOf<String>()
        replay(order, MatchMode.MOMENT, withRule, skipped, mutableListOf())
        println("default same-run rule skips: $skipped")
        assertEquals(setOf("79a1743b2d96", "75dd0a3a1e3d"), skipped.map { it.take(12) }.toSet())
        assertEquals(oracle.size - 2, withRule.rows.size)
    }
}
