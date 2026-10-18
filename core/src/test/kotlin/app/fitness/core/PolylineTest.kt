package app.fitness.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineTest {
    @Test
    fun decodesTheGoogleReferenceExample() {
        // The worked example from Google's polyline documentation.
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0][0], 1e-9); assertEquals(-120.2, pts[0][1], 1e-9)
        assertEquals(40.7, pts[1][0], 1e-9); assertEquals(-120.95, pts[1][1], 1e-9)
        assertEquals(43.252, pts[2][0], 1e-9); assertEquals(-126.453, pts[2][1], 1e-9)
    }

    @Test
    fun geoJsonWriterMatchesTheDecoderOnEveryRealRoute() {
        if (!Golden.available) return
        var routes = 0
        var points = 0
        for (entry in Golden.perFile) {
            for (seg in entry.arr("route").map { it.str()!! }) {
                val expected = Polyline.decode(seg).joinToString(",") {
                    String.format(Locale.ROOT, "[%.5f,%.5f]", it[1], it[0])
                }
                val sb = StringBuilder()
                val n = Polyline.appendGeoJson(seg, sb)
                assertEquals(expected, sb.toString(), "route of ${entry["input"].str()}")
                points += n
            }
            routes++
        }
        println("polyline writer verified on $routes activities, $points points")
        assertTrue(points > 0)
    }
}
