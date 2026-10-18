package app.fitness.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElevationFillTest {
    private val seed: File? = System.getProperty("seed.dir")?.let(::File)

    @Test
    fun csvParserHandlesQuotesBomAndRepeatedHeaders() {
        val csv = "﻿Activity ID,Activity Name,Filename,Distance,Elevation Gain,Distance\r\n" +
            "1,\"Run, with comma\",activities/AB1.fit.gz,5.0,56.0,5000\r\n" +
            "2,\"He said \"\"go\"\"\",activities/two.gpx,1.0,0.0,1000\r\n" +
            "3,Indoor,,0,12,0\r\n" +
            "4,Blank,activities/four.tcx,3.0,,3000\r\n" +
            "5,Multi,\"activities/five.fit\n.gz\",3.0,7.5,3000\r\n"
        val g = StravaCsv.elevationGains(csv)
        assertEquals(setOf("ab1.fit.gz", "five.fit\n.gz"), g.keys)      // zero, blank and nameless rows are left out
        assertEquals(56.0, g["ab1.fit.gz"])
        assertEquals("ab1.fit.gz", StravaCsv.originalName("imports/0123456789ab_AB1.fit.gz"))
        assertEquals(null, StravaCsv.originalName("imports/odd.fit"))
        assertEquals("ab1.fit.gz", StravaCsv.originalName("imports/0123456789ab_0123456789ab_AB1.fit.gz"))   // an archive copy imported again
    }

    @Test
    fun csvWithoutTheColumnsIsRefused() {
        val e = runCatching { StravaCsv.elevationGains("a,b\n1,2\n") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun terrariumPixelsDecodeToHeights() {
        assertEquals(0f, Terrarium.decode(128, 0, 0))
        assertEquals(1.5f, Terrarium.decode(128, 1, 128))
        assertEquals(-10f, Terrarium.decode(127, 246, 0))
    }

    @Test
    fun tilePixelsFollowWebMercator() {
        val (x, y) = Terrarium.tilePixel(0.0, 0.0, 1, 256)
        assertEquals(256.0, x, 1e-9)
        assertEquals(256.0, y, 1e-9)
        val (x2, _) = Terrarium.tilePixel(10.0, -180.0, 13, 256)
        assertEquals(0.0, x2, 1e-9)
    }

    @Test
    fun aFlatSlopeGivesItsClimbAndSamplesAreLaidBackByDistance() {
        // 1 km due north, rising 20 m: a lookup that returns 0.02 m per metre north.
        val lat = DoubleArray(101) { 40.0 + it * 0.00009 }
        val lon = DoubleArray(101) { -75.0 }
        val origin = lat[0]
        val p = TerrainElevation.profile(lat, lon, { pts -> pts.map { (it[0] - origin) * 111_194.9 * 0.02 } })
        assertEquals(101, p.altitude.size)
        assertEquals(20.0, p.gain, 0.5)
        assertTrue(p.altitude.toList().zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0.0, p.altitude.first(), 0.11)
    }

    @Test
    fun noHeightsAnywhereIsAnError() {
        val lat = doubleArrayOf(40.0, 40.001, 40.002)
        val e = runCatching { TerrainElevation.profile(lat, DoubleArray(3) { -75.0 }, { pts -> pts.map { null } }) }.exceptionOrNull()
        assertTrue(e is TerrainException)
    }

    /** The PC stored the profile of 8 real activities; rebuild each from the PC's own cached tiles and compare. */
    @Test
    fun terrainProfilesMatchTheOnesStoredByThePcApp() {
        val dir = seed ?: return
        val tiles = File(dir, "pc-terrain/0d0a521d")
        val side = File(dir, "pc-side-data.json")
        val imports = File(dir, "imports")
        if (!tiles.isDirectory || !side.exists() || !imports.isDirectory) { println("terrain fixtures missing: skipped"); return }
        val terrarium = Terrarium({ z, x, y ->
            val png = File(tiles, "$z/$x/$y.png")
            if (!png.exists()) throw TerrainException("tile $z/$x/$y is not in the PC cache")
            val img = ImageIO.read(png)
            FloatArray(256 * 256) { i -> val rgb = img.getRGB(i % 256, i / 256); Terrarium.decode((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255) }
        })
        val entries = Json.parseToJsonElement(side.readText()).jsonObject.getValue("terrain_elevation").jsonArray.map { it.jsonObject }
        var checked = 0
        for (e in entries) {
            val hash = e.getValue("file_hash").toString().trim('"')
            val file = imports.listFiles()!!.first { it.name.startsWith(hash.take(12)) }
            val a = Importer.parse(file.name, file.readBytes())
            val wantAlt = (e.getValue("altitude") as JsonArray).map { it.toString().toDouble() }
            val wantGain = e.getValue("elevation_gain").toString().toDouble()
            val got = TerrainElevation.profile(a.streams.getValue("lat"), a.streams.getValue("lon"), terrarium)
            assertEquals(wantAlt.size, got.altitude.size, "${hash.take(12)} altitude length")
            val worst = wantAlt.indices.maxOf { abs(wantAlt[it] - got.altitude[it]) }
            println("${hash.take(12)}: gain pc=$wantGain kotlin=${got.gain}, worst altitude difference $worst m")
            assertTrue(worst <= 0.11, "${hash.take(12)} altitude differs by $worst m")
            assertEquals(wantGain, got.gain, 0.11, "${hash.take(12)} gain")
            checked++
        }
        assertEquals(8, checked)
    }

    /** Every Strava override the PC holds is found by matching the CSV's file names to the archived files. */
    @Test
    fun everyPcOverrideIsFoundInTheCsv() {
        val dir = seed ?: return
        val csv = File(dir, "activities.csv")
        val side = File(dir, "pc-side-data.json")
        if (!csv.exists() || !side.exists()) { println("csv or side data missing: skipped"); return }
        val gains = StravaCsv.elevationGains(csv.readText())
        val overrides = Json.parseToJsonElement(side.readText()).jsonObject.getValue("elevation_overrides").jsonArray.map { it.jsonObject }
        var missing = 0
        for (o in overrides) {
            val name = StravaCsv.originalName(o.getValue("source_file").toString().trim('"'))
            val want = o.getValue("elevation_gain").toString().toDouble()
            if (gains[name] != want) missing++
        }
        println("csv rows with a gain: ${gains.size}; PC overrides ${overrides.size}; not matched: $missing")
        assertEquals(0, missing)
    }
}
