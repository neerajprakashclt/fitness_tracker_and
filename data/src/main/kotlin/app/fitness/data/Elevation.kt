package app.fitness.data

import android.graphics.BitmapFactory
import android.util.LruCache
import app.fitness.core.Terrarium
import app.fitness.core.TerrainElevation
import app.fitness.core.TerrainException
import app.fitness.core.TerrainProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Ground height for a GPS track. May use the network; throws [TerrainException] when it cannot answer. */
interface TerrainService {
    suspend fun lookup(lat: DoubleArray, lon: DoubleArray): TerrainProfile
    val provider: String
}

/**
 * Heights from "terrarium" map tiles (AWS Open Data by default; any host serving the same encoding works). Each 256 x 256 tile
 * is downloaded once and kept under [cacheDir], so an area is only ever fetched once. The only thing the host can learn is
 * which tiles were asked for, i.e. roughly the area, not the route. After [GIVE_UP] failures in a row it stops trying, so a
 * bulk import with no connection is not slowed down by every file waiting for a timeout.
 */
class TerrainTiles(
    private val cacheDir: File,
    private val template: String = DEFAULT_URL,
    private val userAgent: String = "FitnessTracker-Android/1.0 (personal, local use)",
) : TerrainService {
    companion object {
        const val DEFAULT_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
        const val GIVE_UP = 5
        private const val TIMEOUT_MS = 20_000
    }

    override val provider = "terrarium"
    private val memory = LruCache<String, FloatArray>(48)
    @Volatile private var failures = 0
    private val root = File(cacheDir, MessageDigest.getInstance("SHA-1").digest(template.toByteArray()).joinToString("") { "%02x".format(it) }.take(8))

    override suspend fun lookup(lat: DoubleArray, lon: DoubleArray): TerrainProfile = withContext(Dispatchers.IO) {
        if (failures >= GIVE_UP) throw TerrainException("gave up after $GIVE_UP failures in a row; turn the option off and on to try again")
        try {
            TerrainElevation.profile(lat, lon, Terrarium(::tile)).also { failures = 0 }
        } catch (e: TerrainException) {
            failures++
            throw e
        }
    }

    private fun tile(z: Int, x: Int, y: Int): FloatArray {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        val file = File(root, "$key.png")
        val png = if (file.exists()) file.readBytes() else download(z, x, y).also { bytes ->
            file.parentFile?.mkdirs()
            File(file.path + ".part").also { it.writeBytes(bytes); it.renameTo(file) }   // never leave half a tile that would be read later
        }
        val grid = try { decode(png) } catch (e: Exception) {
            file.delete()                                                                // a bad tile would fail again next time
            throw TerrainException("the tile at $key is not a terrarium height image (${e.message})", e)
        }
        memory.put(key, grid)
        return grid
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray {
        val url = URL(template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"))
        val host = url.host
        try {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", userAgent)
            try {
                val code = c.responseCode
                if (code == 429) throw TerrainException("$host answered 429. It is limiting how fast you ask: try again later.")
                if (code in listOf(401, 402, 403)) throw TerrainException("$host answered $code. It may now need a key or be restricted.")
                if (code != 200) throw TerrainException("$host answered $code.")
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        } catch (e: TerrainException) {
            throw e
        } catch (e: java.io.IOException) {
            throw TerrainException("could not reach $host: ${e.message}", e)
        }
    }

    private fun decode(png: ByteArray): FloatArray {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("not a PNG")
        require(bmp.width == Terrarium.TILE && bmp.height == Terrarium.TILE) { "${bmp.width}x${bmp.height} pixels, expected ${Terrarium.TILE}x${Terrarium.TILE}" }
        val px = IntArray(Terrarium.TILE * Terrarium.TILE)
        bmp.getPixels(px, 0, Terrarium.TILE, 0, 0, Terrarium.TILE, Terrarium.TILE)
        bmp.recycle()
        return FloatArray(px.size) { Terrarium.decode((px[it] shr 16) and 255, (px[it] shr 8) and 255, px[it] and 255) }
    }
}
