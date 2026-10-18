package app.fitness.core

import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.tan

/** A height source could not answer. The message is shown to the person importing. */
class TerrainException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Heights in metres for (lat, lon) points; null where the source has none. */
fun interface HeightLookup {
    @Throws(TerrainException::class)
    fun heights(points: List<DoubleArray>): List<Double?>
}

/** The result of a terrain look-up: an altitude per original sample, and the total climb in metres. */
class TerrainProfile(val altitude: DoubleArray, val gain: Double)

/**
 * Ground height for activities whose file has GPS but no altitude (PC: `terrain/profile.py`). The track is sampled every
 * [Terrarium.STEP_M] metres along the ground, looked up, and laid back onto the original timeline by distance. Tuned on
 * real data against Strava's own gain for 45 Amazfit activities: a point every 25 m, **no smoothing** and a 2 m climb
 * threshold gave a median error of 3.8%; a 5-point average flattened climbs and gave 14.8%.
 */
object TerrainElevation {
    const val GAIN_THRESHOLD = 2.0
    /** A very long track gets coarser steps rather than thousands of look-ups. */
    const val MAX_SAMPLES = 3000

    fun hasTrack(streams: Map<String, DoubleArray>): Boolean {
        val lat = streams["lat"] ?: return false
        val lon = streams["lon"] ?: return false
        return lat.indices.count { !lat[it].isNaN() && !lon[it].isNaN() } >= 2
    }

    /** numpy `interp`: clamps outside the range, and at a repeated x takes the last of them. */
    internal fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        if (x <= xp[0]) return fp[0]
        if (x >= xp.last()) return fp.last()
        var lo = 0
        var hi = xp.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (xp[mid] <= x) lo = mid + 1 else hi = mid }
        val j = lo - 1
        val span = xp[j + 1] - xp[j]
        return if (span == 0.0) fp[j] else fp[j] + (fp[j + 1] - fp[j]) * (x - xp[j]) / span
    }

    /** Throws [TerrainException] when the source has no height for the area. */
    fun profile(lat: DoubleArray, lon: DoubleArray, lookup: HeightLookup, stepM: Double = Terrarium.STEP_M): TerrainProfile {
        val cumAll = Common.pathDistance(lat, lon)
        val ok = lat.indices.filter { !lat[it].isNaN() && !lon[it].isNaN() }
        val cum = DoubleArray(ok.size) { cumAll[ok[it]] }
        val la = DoubleArray(ok.size) { lat[ok[it]] }
        val lo = DoubleArray(ok.size) { lon[ok[it]] }
        val total = cum.last()

        val step = max(stepM, total / MAX_SAMPLES)
        val at = if (total > 0) {
            val n = kotlin.math.ceil(total / step).toInt()
            DoubleArray(n + 1) { if (it < n) it * step else total }
        } else doubleArrayOf(0.0)
        val points = at.map { doubleArrayOf(interp(it, cum, la), interp(it, cum, lo)) }
        val got = lookup.heights(points)
        val good = got.indices.filter { got[it] != null }
        if (good.isEmpty()) throw TerrainException("the source has no height data for this area")
        val gx = DoubleArray(good.size) { at[good[it]] }
        val gy = DoubleArray(good.size) { got[good[it]]!! }
        val heights = DoubleArray(at.size) { interp(at[it], gx, gy) }          // a few missing points are bridged

        val gain = Common.elevationGain(heights, GAIN_THRESHOLD) ?: 0.0
        val altitude = DoubleArray(lat.size) { Double.NaN }
        for ((k, i) in ok.withIndex()) altitude[i] = round(interp(cum[k], at, heights) * 10) / 10
        var last = altitude[ok[0]]
        for (i in altitude.indices) { if (altitude[i].isNaN()) altitude[i] = last else last = altitude[i] }
        return TerrainProfile(altitude, round(gain * 10) / 10)
    }
}

/**
 * Heights from "terrarium" map tiles, which encode height in the pixel colour: (R * 256 + G + B / 256) - 32768 (PC:
 * `terrain/providers.py`). Fetching, caching and decoding a tile are the platform's job and arrive through [tile].
 */
class Terrarium(
    /** The 256 x 256 heights of a tile, row by row from the top; throws [TerrainException] when it cannot be had. */
    private val tile: (z: Int, x: Int, y: Int) -> FloatArray,
    private val zoom: Int = ZOOM,
) : HeightLookup {
    companion object {
        const val ZOOM = 13
        const val TILE = 256
        const val STEP_M = 25.0
        const val MAX_TILES_PER_ACTIVITY = 200

        fun decode(r: Int, g: Int, b: Int): Float = (r * 256.0 + g + b / 256.0 - 32768).toFloat()

        /** Global pixel coordinates (x east, y south) of a point in the web-mercator tile grid. */
        fun tilePixel(lat: Double, lon: Double, zoom: Int, size: Int = TILE): Pair<Double, Double> {
            val n = (1 shl zoom).toDouble() * size
            val la = max(-85.0511, min(85.0511, lat))
            val x = (lon + 180.0) / 360.0 * n
            val y = (1.0 - asinh(tan(Math.toRadians(la))) / Math.PI) / 2.0 * n
            return x to y
        }
    }

    override fun heights(points: List<DoubleArray>): List<Double?> {
        class Cell(val x0: Int, val y0: Int, val tx: Double, val ty: Double)
        val plan = points.map { p ->
            val (px, py) = tilePixel(p[0], p[1], zoom)
            val fx = px - 0.5                                           // pixel centres
            val fy = py - 0.5
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            Cell(x0, y0, fx - x0, fy - y0)
        }
        val needed = HashSet<Pair<Int, Int>>()
        for (c in plan) for (dx in 0..1) for (dy in 0..1) needed += Math.floorDiv(c.x0 + dx, TILE) to Math.floorDiv(c.y0 + dy, TILE)
        if (needed.size > MAX_TILES_PER_ACTIVITY) throw TerrainException("the route spans ${needed.size} map tiles, too many to look up")
        val side = 1 shl zoom
        return plan.map { c ->
            val v = DoubleArray(4)
            var missing = false
            for (dy in 0..1) for (dx in 0..1) {
                val gx = c.x0 + dx
                val gy = c.y0 + dy
                val tileX = Math.floorDiv(gx, TILE)
                val tileY = Math.floorDiv(gy, TILE)
                if (tileY !in 0 until side) { missing = true; continue }     // off the top or bottom of the world
                val grid = tile(zoom, Math.floorMod(tileX, side), tileY)
                v[dy * 2 + dx] = grid[(gy - tileY * TILE) * TILE + (gx - tileX * TILE)].toDouble()
            }
            if (missing) null else {
                val top = v[0] * (1 - c.tx) + v[1] * c.tx
                val bottom = v[2] * (1 - c.tx) + v[3] * c.tx
                top * (1 - c.ty) + bottom * c.ty
            }
        }
    }
}
