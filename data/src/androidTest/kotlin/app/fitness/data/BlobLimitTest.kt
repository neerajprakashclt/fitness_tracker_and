package app.fitness.data

import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Phase 0 spike (risk R6): Android reads SQLite rows through a CursorWindow of about 2 MB. How big can a stream blob
 * be through Room, and does gzip keep real activities under the limit? Results are logged under the tag "BlobSpike".
 */
class BlobLimitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun db() = Room.databaseBuilder(context, SpikeDb::class.java, "blob-spike.db").build().also {
        context.deleteDatabase("blob-spike.db")
    }

    /** Text shaped like a real stream JSON: numbers that compress about 3 to 4 times. */
    private fun streamLikeJson(bytes: Int): ByteArray {
        val rnd = Random(7)
        val sb = StringBuilder(bytes + 64)
        var hr = 140.0
        var t = 0
        sb.append("{\"time\":[")
        while (sb.length < bytes) {
            hr += rnd.nextGaussian(); t++
            sb.append(t).append(',').append("%.1f".format(hr)).append(',')
        }
        return sb.toString().toByteArray()
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).readBytes()

    @Test
    fun findTheRowSizeLimit() = runBlocking {
        val db = db()
        val dao = db.blobs()
        val results = StringBuilder()
        var largestOk = 0
        for (kb in listOf(256, 1024, 1300, 1800, 2000, 2200, 3000, 6000, 12000)) {
            val data = ByteArray(kb * 1024).also { Random(kb.toLong()).nextBytes(it) }
            dao.put(StreamBlob("h$kb", data))
            val outcome = try {
                val back = dao.read("h$kb")
                if (back != null && back.contentEquals(data)) { largestOk = kb; "ok" } else "wrong data (${back?.size})"
            } catch (e: Throwable) {
                "FAILED ${e::class.simpleName}: ${e.message?.take(90)}"
            }
            results.append("\n  $kb KB: $outcome")
        }
        Log.i("BlobSpike", "plain blobs through Room:$results\n  largest that read back intact: $largestOk KB")
        db.close()
        assertTrue(largestOk >= 1024, "a 1 MB row must work")
    }

    @Test
    fun gzipKeepsTheLargestRealStreamWellUnderTheLimit() = runBlocking {
        val db = db()
        val dao = db.blobs()
        // The PC's largest stream JSON is 1.29 MB; try that and a 4x longer activity.
        val lines = StringBuilder()
        for (mb in listOf(1.3, 5.0)) {
            val raw = streamLikeJson((mb * 1024 * 1024).toInt())
            val packed = gzip(raw)
            dao.put(StreamBlob("g$mb", packed))
            val back = dao.read("g$mb")!!
            assertContentEquals(raw, gunzip(back))
            lines.append("\n  ${"%.1f".format(mb)} MB json -> ${packed.size / 1024} KB gzipped, read back and unzipped intact")
        }
        Log.i("BlobSpike", "gzipped streams through Room:$lines")
        db.close()
    }
}
