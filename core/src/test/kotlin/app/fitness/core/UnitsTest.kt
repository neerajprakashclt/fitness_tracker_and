package app.fitness.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnitsTest {
    private val metric = Formatter(Units.METRIC)
    private val imperial = Formatter(Units.IMPERIAL)

    @Test
    fun formatsLikeThePcApp() {
        assertEquals("5:39 /km", metric.pace(1000 / 339.0))
        assertEquals("–", metric.pace(0.1))
        assertEquals("27.0 km/h", metric.speed(7.5))
        assertEquals("8.42 km", metric.distance(8420.0))
        assertEquals("5.23 mi", imperial.distance(8420.0))
        assertEquals("1,234 m", metric.elevation(1234.0))
        assertEquals("4,049 ft", imperial.elevation(1234.0))
        assertEquals("5:09", Formatter.duration(309.0))
        assertEquals("1:02:03", Formatter.duration(3723.0))
        assertEquals("3h 00m", Formatter.hours(10799.6))
        assertEquals("Pace", metric.paceOrSpeedLabel("walking"))
        assertEquals("Speed", metric.paceOrSpeedLabel("cycling"))
    }

    @Test
    fun normalisesSports() {
        assertEquals("running", Common.normalizeSport("9"))
        assertEquals("cycling", Common.normalizeSport("Ride"))
        assertEquals("running", Common.normalizeSport("Trail Run"))
        assertEquals("weight_training", Common.normalizeSport("Weight Training"))
        assertEquals("unknown", Common.normalizeSport(null))
        assertEquals("running", Common.inferFootSport(5000.0, 1500.0))
        assertEquals("walking", Common.inferFootSport(5000.0, 3600.0))
        assertEquals("unknown", Common.inferFootSport(null, 3600.0))
    }

    @Test
    fun streamsSurviveTheFileFormat() {
        val streams = mapOf(
            "time" to doubleArrayOf(0.0, 1.0, 2.0),
            "heart_rate" to doubleArrayOf(140.0, Double.NaN, 150.5),
            "lat" to doubleArrayOf(40.123456789, 40.1234568, Double.NaN),
        )
        val back = StreamCodec.decode(StreamCodec.encode(streams))
        assertEquals(streams.keys, back.keys)
        for ((k, v) in streams) assertContentEquals(v, back.getValue(k), k)
    }

    @Test
    fun namesAndHashesMatchThePcArchive() {
        assertEquals("fit", Importer.extension("Run 1.FIT.gz").first)
        assertEquals("abcdef012345_My_Run__1_.fit.gz", Importer.archiveName("abcdef0123456789", "My Run (1).fit.gz"))
        assertFailsWith<ImportFailed> { Importer.decode("notes.txt", ByteArray(3)) }
        assertFailsWith<ImportFailed> { Importer.decode("broken.fit.gz", ByteArray(10)) }
    }
}

class TimesTest {
    @Test
    fun formatsLikeThePcAndParsesBack() {
        val t = java.time.Instant.parse("2026-08-27T12:08:03Z")
        assertEquals("2026-08-27T12:08:03+00:00", Times.format(t))
        assertEquals("2026-08-27T12:08:03.500000+00:00", Times.format(t.plusMillis(500)))
        assertEquals(t, Times.parse(Times.format(t)))
        assertEquals(t.plusMillis(500), Times.parse(Times.format(t.plusMillis(500))))
    }
}

class ChartSeriesTest {
    @Test
    fun bucketsAverageAndSkipGaps() {
        val time = DoubleArray(1000) { it.toDouble() }
        val v = DoubleArray(1000) { if (it in 100..199) Double.NaN else 100.0 + (it % 2) }
        val s = ChartSeries.bucketMean(time, v, maxPoints = 100)
        assertEquals(true, s.size in 80..90)             // the 100 NaN samples leave a hole
        assertEquals(100.5, s.y[0], 1e-9)
        assertEquals(true, s.x.toList().zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun bucketTimesAreWholeSecondsSoLongActivitiesCanBeCharted() {
        // A marathon: 15,000 samples into 400 buckets of 38 gives bucket means like 18.5 and 56.5 ... never more than whole seconds.
        val time = DoubleArray(15001) { it.toDouble() }
        val s = ChartSeries.bucketMean(time, DoubleArray(15001) { 150.0 })
        assertEquals(true, s.x.all { it == Math.rint(it) })
    }

    @Test
    fun clippingKeepsGlitchesFromFlatteningTheChart() {
        val y = DoubleArray(100) { 5.0 + it * 0.01 }.also { it[50] = 500.0 }
        assertEquals(true, ChartSeries.clipToQuantiles(y).max() < 7.0)
    }

    @Test
    fun findsTheSampleForATime() {
        val t = doubleArrayOf(0.0, 1.0, 2.0, 5.0, 6.0)
        assertEquals(3, ChartSeries.indexAtTime(t, 3.0))
        assertEquals(0, ChartSeries.indexAtTime(t, -1.0))
        assertEquals(4, ChartSeries.indexAtTime(t, 99.0))
    }
}

class SimilarTest {
    private val t0 = java.time.Instant.parse("2026-05-01T12:00:00Z")
    private val ny = java.time.ZoneId.of("America/New_York")

    @Test
    fun classifiesHowTwoStartsRelate() {
        assertEquals(SimilarKind.MOMENT, Similar.classify(t0.plusSeconds(90), t0, ny))
        assertEquals(SimilarKind.MOMENT, Similar.classify(t0.minusSeconds(120), t0, ny))
        // Local time written as if it were UTC: exactly four hours off (New York in summer).
        assertEquals(SimilarKind.ZONE, Similar.classify(t0.plusSeconds(4 * 3600 + 30), t0, ny))
        assertEquals(SimilarKind.ZONE, Similar.classify(t0.minusSeconds(5 * 3600), t0, ny))
        // Three hours apart and 4 minutes apart on one day are separate runs unless day mode is on.
        assertEquals(SimilarKind.DAY, Similar.classify(t0.plusSeconds(3 * 3600 + 400), t0, ny))
        assertEquals(SimilarKind.DAY, Similar.classify(t0.plusSeconds(240), t0, ny))
        assertEquals(null, Similar.classify(t0.plusSeconds(30 * 3600 + 600), t0, ny))
    }

    @Test
    fun findsTheStrongestMatchAndHonoursTheMode() {
        val near = SimilarCandidate("a", t0.plusSeconds(60), 5000.0)
        val farDistance = SimilarCandidate("b", t0, 9000.0)
        val zoneSlip = SimilarCandidate("c", t0.minusSeconds(4 * 3600), 5100.0)
        val m = Similar.find(t0, 5050.0, listOf(zoneSlip, farDistance, near), ny)!!
        assertEquals("a", m.fileHash)
        assertEquals(SimilarKind.MOMENT, m.kind)
        assertEquals(-60.0, m.offsetSeconds)

        // A timezone slip needs a closer distance (5%) than a same-moment match (10%).
        assertEquals(null, Similar.find(t0, 5000.0, listOf(SimilarCandidate("z", t0.minusSeconds(4 * 3600), 5400.0)), ny))
        assertEquals(null, Similar.find(t0, null, listOf(near), ny))

        assertEquals(true, Similar.skips(SimilarKind.ZONE, MatchMode.MOMENT))
        assertEquals(false, Similar.skips(SimilarKind.DAY, MatchMode.MOMENT))
        assertEquals(true, Similar.skips(SimilarKind.DAY, MatchMode.DAY))
        assertEquals(false, Similar.skips(SimilarKind.MOMENT, MatchMode.OFF))
    }
}
