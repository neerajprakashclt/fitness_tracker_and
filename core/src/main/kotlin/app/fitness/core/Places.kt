package app.fitness.core

import java.util.zip.GZIPInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** A populated place from the GeoNames "cities1000" list. */
class Place(val name: String, val region: String, val country: String, val lat: Double, val lon: Double) {
    val key get() = Triple(name, region, country)
}

/** A city: nearby gazetteer places folded into the most-visited one. */
class PlaceArea(val name: String, val region: String, val country: String, val runsByYear: Map<String, Int>, val places: List<String>)

/**
 * Which cities you have run in, found offline from your own GPS tracks (PC: `places.py`). Each route is sampled at a handful
 * of points and each point is matched to the nearest place in a bundled copy of GeoNames cities1000 (CC BY 4.0, geonames.org),
 * the same list the PC app uses. Nothing is sent anywhere. The gazetteer is neighbourhood-sized, so places within
 * [CLUSTER_KM] of a more-visited place are folded into it, which is what "a city" means to a runner.
 */
object Places {
    const val SAMPLES_PER_ROUTE = 7
    const val CLUSTER_KM = 6.5
    private const val EARTH_RADIUS_KM = 6371.0

    private class Gazetteer(val lat: DoubleArray, val lon: DoubleArray, val name: Array<String>, val region: Array<String>, val country: Array<String>) {
        private val cellStart = IntArray(CELLS + 1)
        private val order: IntArray

        init {
            val cells = IntArray(lat.size) { cell(lat[it], lon[it]) }
            for (c in cells) cellStart[c + 1]++
            for (i in 0 until CELLS) cellStart[i + 1] += cellStart[i]
            val fill = cellStart.copyOf()
            order = IntArray(lat.size)
            for (i in lat.indices) order[fill[cells[i]]++] = i
        }

        /** The nearest place by straight distance in degrees (what the PC's k-d tree does), searching outwards ring by ring. */
        fun nearest(qlat: Double, qlon: Double): Int {
            val r0 = floor(qlat).toInt()
            val c0 = floor(qlon).toInt()
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            var r = 0
            while (r <= 360) {
                for (dr in -r..r) for (dc in -r..r) {
                    if (maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != r) continue
                    val la = r0 + dr
                    val lo = c0 + dc
                    if (la < -90 || la > 90 || lo < -180 || lo > 180) continue
                    val c = (minOf(la, 89) + 90) * 360 + (minOf(lo, 179) + 180)
                    for (k in cellStart[c] until cellStart[c + 1]) {
                        val i = order[k]
                        val d = (lat[i] - qlat) * (lat[i] - qlat) + (lon[i] - qlon) * (lon[i] - qlon)
                        if (d < bestD) { bestD = d; best = i }
                    }
                }
                if (best >= 0 && sqrt(bestD) <= r) break
                r++
            }
            return best
        }

        companion object {
            const val CELLS = 180 * 360
            fun cell(lat: Double, lon: Double) = (minOf(floor(lat).toInt(), 89) + 90) * 360 + (minOf(floor(lon).toInt(), 179) + 180)
        }
    }

    private val gazetteer: Gazetteer by lazy {
        val stream = Places::class.java.getResourceAsStream("/places/cities1000.tsv.gz") ?: error("places/cities1000.tsv.gz is missing")
        val rows = GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines -> lines.map { it.split('\t') }.toList() }
        Gazetteer(
            DoubleArray(rows.size) { rows[it][0].toDouble() }, DoubleArray(rows.size) { rows[it][1].toDouble() },
            Array(rows.size) { rows[it][2] }, Array(rows.size) { rows[it][3] }, Array(rows.size) { rows[it][4] },
        )
    }

    fun nearest(lat: Double, lon: Double): Place = gazetteer.let { g -> g.nearest(lat, lon).let { Place(g.name[it], g.region[it], g.country[it], g.lat[it], g.lon[it]) } }

    /** Up to [count] points spread along a route, always including its first and last. */
    fun sample(polylines: List<String>, count: Int = SAMPLES_PER_ROUTE): List<DoubleArray> {
        val pts = RouteMatch.track(polylines)
        if (pts.isEmpty()) return emptyList()
        if (pts.size <= count) return pts
        val step = (pts.size - 1.0) / (count - 1)
        return List(count) { pts[round(it * step).toInt()] }
    }

    /** The distinct places a route passes through, in the order first met. */
    fun visited(polylines: List<String>): List<Place> {
        val seen = LinkedHashMap<Triple<String, String, String>, Place>()
        for (p in sample(polylines)) nearest(p[0], p[1]).let { seen.putIfAbsent(it.key, it) }
        return seen.values.toList()
    }

    private fun kmBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
    }

    /** [runs]: each run's year and the places it passed, runs with none left out. Most-run area first. */
    fun areas(runs: List<Pair<String, List<Place>>>, clusterKm: Double = CLUSTER_KM): List<PlaceArea> {
        val runsAt = LinkedHashMap<Triple<String, String, String>, Int>()
        val where = HashMap<Triple<String, String, String>, Place>()
        for ((_, found) in runs) for (p in found) { runsAt.merge(p.key, 1, Int::plus); where[p.key] = p }
        class Group(val place: Place, val places: MutableList<String> = ArrayList(), val years: MutableMap<String, Int> = HashMap())
        val groups = ArrayList<Group>()
        val groupOf = HashMap<Triple<String, String, String>, Int>()
        for (key in runsAt.keys.sortedByDescending { runsAt.getValue(it) }) {
            val at = where.getValue(key)
            var home = groups.indexOfFirst { kmBetween(it.place.lat, it.place.lon, at.lat, at.lon) <= clusterKm }
            if (home < 0) { groups += Group(at); home = groups.size - 1 }
            groups[home].places += key.first
            groupOf[key] = home
        }
        for ((year, found) in runs) for (i in found.map { groupOf.getValue(it.key) }.toSet()) groups[i].years.merge(year, 1, Int::plus)
        return groups.map { PlaceArea(it.place.name, it.place.region, it.place.country, it.years.toSortedMap(), it.places) }
            .sortedByDescending { it.runsByYear.values.sum() }
    }
}
