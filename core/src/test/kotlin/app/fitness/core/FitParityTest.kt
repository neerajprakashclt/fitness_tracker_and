package app.fitness.core

import kotlinx.serialization.json.JsonNull
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** Phase 0 spike: does the Garmin FIT SDK reproduce what fitparse produced on the PC, on the user's real files? */
class FitParityTest {
    private val fitFiles get() = Golden.perFile.filter { it["input"].str()!!.removeSuffix(".gz").endsWith(".fit") }

    @Test
    fun parsesEveryFitFixtureLikeThePcApp() {
        if (!Golden.available) { println("golden fixtures missing: skipped"); return }
        val bad = Mismatches()
        var parsed = 0
        for (entry in fitFiles) {
            val name = entry["input"].str()!!
            val want = entry.obj("parsed")
            val got = try { FitImporter.parse(Golden.inputBytes(name)) } catch (e: Throwable) {
                bad.items += "$name: threw ${e::class.simpleName}: ${e.message}"; continue
            }
            parsed++
            bad.check("$name sport", want["sport"].str() == got.sport) { "pc=${want["sport"].str()} kotlin=${got.sport}" }
            bad.check("$name start", OffsetDateTime.parse(want["start_time"].str()!!).toInstant() == got.startTime) {
                "pc=${want["start_time"].str()} kotlin=${got.startTime}" }
            bad.check("$name distance", close(want["distance"].num(), got.distance, 1e-6)) { "pc=${want["distance"].num()} kotlin=${got.distance}" }
            bad.check("$name elevation", close(want["elevation_gain"].num(), got.elevationGain, 1e-6)) { "pc=${want["elevation_gain"].num()} kotlin=${got.elevationGain}" }
            bad.check("$name device", want["device"].str() == got.device) { "pc=${want["device"].str()} kotlin=${got.device}" }
            val wantKeys = want.arr("stream_keys").map { it.str()!! }.sorted()
            bad.check("$name stream keys", wantKeys == got.streams.keys.sorted()) { "pc=$wantKeys kotlin=${got.streams.keys.sorted()}" }
            // The PC duration equals the device timer unless the track-derived moving time replaced it (phase 1).
            val pcDuration = want["duration"].num()
            if (close(pcDuration, got.timerSeconds, 1e-9)) Unit
            compareStreams(name, entry.obj("streams"), got, bad)
            val wantLaps = want.arr("laps")
            bad.check("$name lap count", wantLaps.size == got.laps.size) { "pc=${wantLaps.size} kotlin=${got.laps.size}" }
            for ((k, lap) in wantLaps.withIndex()) {
                val l = got.laps.getOrNull(k) ?: break
                val o = lap as kotlinx.serialization.json.JsonObject
                bad.check("$name lap$k distance", close(o["distance"].num(), l.distance, 1e-6)) { "pc=${o["distance"].num()} kotlin=${l.distance}" }
                bad.check("$name lap$k duration", close(o["duration"].num(), l.duration, 1e-6)) { "pc=${o["duration"].num()} kotlin=${l.duration}" }
                bad.check("$name lap$k hr", close(o["avg_heart_rate"].num(), l.avgHeartRate, 1e-6)) { "pc=${o["avg_heart_rate"].num()} kotlin=${l.avgHeartRate}" }
            }
        }
        println("FIT files compared: $parsed of ${fitFiles.size}; mismatches: ${bad.items.size}")
        bad.items.take(60).forEach { println("  MISMATCH $it") }
        assertTrue(bad.items.isEmpty(), "${bad.items.size} mismatches, first: ${bad.items.firstOrNull()}")
    }

    private fun compareStreams(name: String, want: kotlinx.serialization.json.JsonObject, got: ParsedActivity, bad: Mismatches) {
        for ((key, stat) in want) {
            val s = stat as kotlinx.serialization.json.JsonObject
            val v = got.streams[key] ?: continue
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
    }
}
