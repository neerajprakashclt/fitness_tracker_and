package app.fitness.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * One real tile from the real service, for a public place (Boulder, Colorado, about 1,650 m): the download, the PNG decode on
 * Android and the interpolation all have to work together. Skipped (not failed) when the phone has no connection.
 */
class TerrainLiveTest {
    @Test
    fun aRealTileGivesTheHeightOfBoulder() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "terrain-live-test").apply { deleteRecursively() }
        val tiles = TerrainTiles(dir)
        // A short track north through Boulder's Pearl Street area.
        val lat = DoubleArray(20) { 40.0150 + it * 0.0004 }
        val lon = DoubleArray(20) { -105.2705 }
        val p = try { tiles.lookup(lat, lon) } catch (e: app.fitness.core.TerrainException) {
            org.junit.Assume.assumeTrue("no connection to the tile host: ${e.message}", false); return@runBlocking
        }
        println("Boulder: altitude ${p.altitude.first()} to ${p.altitude.last()} m, gain ${p.gain}; tiles cached: ${dir.walkTopDown().count { it.extension == "png" }}")
        assertTrue(p.altitude.all { it in 1500.0..1800.0 }, "heights should be near 1,650 m: ${p.altitude.toList()}")
        assertTrue(dir.walkTopDown().any { it.extension == "png" }, "the tile should be cached on disk")
        // A second look-up needs no network: the tile is cached.
        val again = TerrainTiles(dir).lookup(lat, lon)
        assertTrue(again.altitude.contentEquals(p.altitude))
    }
}
