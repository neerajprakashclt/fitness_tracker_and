package app.fitness.core

import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** Does the Kotlin import pipeline reproduce what the PC app stored, on the user's real FIT, GPX and TCX files? */
class ImportParityTest {
    @Test
    fun parsesEveryFixtureLikeThePcApp() {
        if (!Golden.available) { println("golden fixtures missing: skipped"); return }
        val bad = Mismatches()
        val perFormat = sortedMapOf<String, Int>()
        for (entry in Golden.perFile) {
            val name = entry["input"].str()!!
            val want = entry.obj("parsed")
            val raw = Golden.rawBytes(name)
            val decoded = try { Importer.decode(name, raw) } catch (e: Throwable) { bad.items += "$name: decode threw ${e.message}"; continue }
            // sha256_raw is of the file as archived (compressed); the PC's file_hash is of the decompressed bytes, and the
            // archive name starts with its first 12 characters.
            if (Regex("[0-9a-f]{12}_.*").matches(name)) bad.check("$name hash", name.take(12) == decoded.fileHash.take(12)) { "archive prefix=${name.take(12)} kotlin=${decoded.fileHash}" }
            if (!name.endsWith(".gz")) bad.check("$name raw hash", entry["sha256_raw"].str() == decoded.fileHash) { "pc=${entry["sha256_raw"].str()}" }
            val got = try { Importer.parse(decoded, name) } catch (e: Throwable) {
                bad.items += "$name: threw ${e::class.simpleName}: ${e.message}"; continue
            }
            perFormat.merge(decoded.format, 1, Int::plus)
            bad.check("$name sport", want["sport"].str() == got.sport) { "pc=${want["sport"].str()} kotlin=${got.sport}" }
            bad.check("$name start", OffsetDateTime.parse(want["start_time"].str()!!).toInstant() == got.startTime) {
                "pc=${want["start_time"].str()} kotlin=${got.startTime}" }
            bad.check("$name duration", close(want["duration"].num(), got.duration, 1e-9, 1e-6)) { "pc=${want["duration"].num()} kotlin=${got.duration}" }
            bad.check("$name distance", close(want["distance"].num(), got.distance, 1e-9, 1e-6)) { "pc=${want["distance"].num()} kotlin=${got.distance}" }
            bad.check("$name elevation", close(want["elevation_gain"].num(), got.elevationGain, 1e-9, 1e-6)) { "pc=${want["elevation_gain"].num()} kotlin=${got.elevationGain}" }
            bad.check("$name device", want["device"].str() == got.device) { "pc=${want["device"].str()} kotlin=${got.device}" }
            val wantKeys = want.arr("stream_keys").map { it.str()!! }.sorted()
            bad.check("$name stream keys", wantKeys == got.streams.keys.sorted()) { "pc=$wantKeys kotlin=${got.streams.keys.sorted()}" }
            compareStreams(name, entry.obj("streams"), got, bad)
            compareLaps(name, want, got, bad)
            val wantRoute = entry.arr("route").map { it.str()!! }
            val gotRoute = Routes.segments(got.streams)
            bad.check("$name route", wantRoute == gotRoute) { "pc=${wantRoute.map { it.length }} kotlin=${gotRoute.map { it.length }}" }
        }
        println("files compared: $perFormat of ${Golden.perFile.size}; mismatches: ${bad.items.size}")
        bad.items.take(80).forEach { println("  MISMATCH $it") }
        assertTrue(bad.items.isEmpty(), "${bad.items.size} mismatches, first: ${bad.items.firstOrNull()}")
    }

    private fun compareLaps(name: String, want: JsonObject, got: ParsedActivity, bad: Mismatches) {
        val wantLaps = want.arr("laps")
        bad.check("$name lap count", wantLaps.size == got.laps.size) { "pc=${wantLaps.size} kotlin=${got.laps.size}" }
        for ((k, lap) in wantLaps.withIndex()) {
            val l = got.laps.getOrNull(k) ?: break
            val o = lap as JsonObject
            val wantStart = o["start_time"].str()?.let { OffsetDateTime.parse(it).toInstant() }
            bad.check("$name lap$k start", wantStart == l.startTime) { "pc=$wantStart kotlin=${l.startTime}" }
            for ((key, v) in listOf("distance" to l.distance, "duration" to l.duration, "avg_heart_rate" to l.avgHeartRate,
                "max_heart_rate" to l.maxHeartRate, "avg_power" to l.avgPower, "avg_speed" to l.avgSpeed, "elevation_gain" to l.elevationGain)) {
                bad.check("$name lap$k $key", close(o[key].num(), v, 1e-9, 1e-6)) { "pc=${o[key].num()} kotlin=$v" }
            }
        }
    }

    private fun compareStreams(name: String, want: JsonObject, got: ParsedActivity, bad: Mismatches) {
        for ((key, stat) in want) {
            val s = stat as JsonObject
            val v = got.streams[key]
            if (v == null) { bad.items += "$name $key: missing in Kotlin"; continue }
            val nn = v.filter { !it.isNaN() }
            bad.check("$name $key len", s["len"].num()!!.toInt() == v.size) { "pc=${s["len"].num()} kotlin=${v.size}" }
            bad.check("$name $key non_null", s["non_null"].num()!!.toInt() == nn.size) { "pc=${s["non_null"].num()} kotlin=${nn.size}" }
            bad.check("$name $key sum", close(s["sum"].num(), nn.sum().takeIf { nn.isNotEmpty() }, 1e-7, 1e-6)) { "pc=${s["sum"].num()} kotlin=${nn.sum()}" }
            bad.check("$name $key min", close(s["min"].num(), nn.minOrNull(), 1e-9, 1e-7)) { "pc=${s["min"].num()} kotlin=${nn.minOrNull()}" }
            bad.check("$name $key max", close(s["max"].num(), nn.maxOrNull(), 1e-9, 1e-7)) { "pc=${s["max"].num()} kotlin=${nn.maxOrNull()}" }
            val first = s.arr("first"); val last = s.arr("last")
            for ((idx, e) in first.withIndex()) {
                val a = v.getOrNull(idx)?.takeIf { !it.isNaN() }
                bad.check("$name $key first[$idx]", close(e.num(), a, 1e-9, 1e-7)) { "pc=${e.num()} kotlin=$a" }
            }
            for ((idx, e) in last.withIndex()) {
                val a = v.getOrNull(v.size - last.size + idx)?.takeIf { !it.isNaN() }
                bad.check("$name $key last[$idx]", close(e.num(), a, 1e-9, 1e-7)) { "pc=${e.num()} kotlin=$a" }
            }
        }
        for (key in got.streams.keys) if (key !in want) bad.items += "$name $key: in Kotlin but not in the PC streams"
    }
}
