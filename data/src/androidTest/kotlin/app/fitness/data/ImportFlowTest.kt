package app.fitness.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.fitness.core.Importer
import app.fitness.core.MatchMode
import app.fitness.core.UntypedSport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The import, duplicate and delete rules against a real Room database and real files on the device. */
class ImportFlowTest {
    private lateinit var db: FitnessDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var dir: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(ctx.cacheDir, "import-flow-test").apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        repo = ActivityRepository(db, dir)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    /** Twelve points heading north at about 4 m/s, one every 5 s, with heart rate. */
    private fun gpx(type: String? = "9"): ByteArray {
        val pts = (0 until 12).joinToString("") { i ->
            """<trkpt lat="${40.0 + i * 0.00018}" lon="-75.0"><ele>${100 + i}</ele><time>2026-05-01T10:00:${"%02d".format(i * 5)}Z</time>
               <extensions><gpxtpx:TrackPointExtension xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"><gpxtpx:hr>${140 + i}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>"""
        }
        val typeTag = if (type != null) "<type>$type</type>" else ""
        return """<?xml version="1.0"?><gpx version="1.1" creator="TestWatch"><trk>$typeTag<trkseg>$pts</trkseg></trk></gpx>""".toByteArray()
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun importsOnceAndTreatsTheSameContentAsADuplicate() = runBlocking<Unit> {
        val first = repo.import("run.gpx", gpx())
        assertIs<ImportOutcome.Imported>(first)
        assertEquals("running", first.sport)

        assertIs<ImportOutcome.Duplicate>(repo.import("run.gpx", gpx()))
        // The hash is of the decompressed content, so the gzipped file is the same activity.
        assertIs<ImportOutcome.Duplicate>(repo.import("copy.gpx.gz", gzip(gpx())))

        val list = repo.list(null, null, null).first()
        assertEquals(1, list.size)
        val a = list[0]
        assertEquals(Importer.decode("run.gpx", gpx()).fileHash, a.fileHash)
        assertTrue(a.distance!! in 150.0..260.0, "distance ${a.distance}")
        assertTrue(a.duration!! in 40.0..60.0, "duration ${a.duration}")
        assertEquals("TestWatch", a.device)
        assertEquals(1, a.route.size)
        assertTrue(File(dir, "imports/${Importer.archiveName(a.fileHash, "run.gpx")}").exists())
        val streams = repo.streams(a.fileHash)!!
        assertEquals(12, streams.getValue("time").size)
        assertEquals(140.0, streams.getValue("heart_rate")[0])
    }

    @Test
    fun deleteRemovesTheActivityButKeepsTheRawFileAndLeavesATombstone() = runBlocking<Unit> {
        val hash = (repo.import("run.gpx", gpx()) as ImportOutcome.Imported).fileHash
        repo.delete(listOf(hash))

        assertEquals(0, repo.list(null, null, null).first().size)
        assertEquals(null, repo.detail(hash))
        assertEquals(null, repo.streams(hash))
        assertTrue(File(dir, "imports/${Importer.archiveName(hash, "run.gpx")}").exists(), "raw file kept")
        assertEquals(listOf("put", "delete"), db.activities().changes().map { it.op })

        // After a delete the same file can be imported again.
        assertIs<ImportOutcome.Imported>(repo.import("run.gpx", gpx()))
    }

    @Test
    fun untypedFilesCanBeGuessedAsRunningOrWalking() = runBlocking<Unit> {
        val kept = repo.import("a.gpx", gpx(type = null))
        assertEquals("unknown", (kept as ImportOutcome.Imported).sport)
        repo.delete(listOf(kept.fileHash))
        val guessed = repo.import("a.gpx", gpx(type = null), UntypedSport.GUESS_FOOT)
        assertEquals("running", (guessed as ImportOutcome.Imported).sport)      // about 4 m/s
    }

    @Test
    fun badFilesFailWithAMessageAndStoreNothing() = runBlocking<Unit> {
        assertIs<ImportOutcome.Failed>(repo.import("notes.txt", "hello".toByteArray()))
        val broken = repo.import("broken.gpx", "<gpx><trk></trk></gpx>".toByteArray())
        assertIs<ImportOutcome.Failed>(broken)
        assertEquals(0, repo.list(null, null, null).first().size)
    }
}
