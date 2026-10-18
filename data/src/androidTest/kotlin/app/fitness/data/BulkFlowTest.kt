package app.fitness.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import app.fitness.core.MatchMode
import app.fitness.core.SimilarKind
import app.fitness.core.UntypedSport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Same-run detection, rebuild, derived data, backup and the schema migration, against real Room and real files on the device. */
class BulkFlowTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FitnessDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var dir: File

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FitnessDatabase::class.java)

    @Before
    fun setUp() {
        dir = File(ctx.cacheDir, "bulk-flow-test").apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        repo = ActivityRepository(db, dir)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    /** About 4 m/s heading north for a minute on May [day]; [startSecond] moves the start within the minute. */
    private fun gpx(startSecond: Int = 0, points: Int = 13, day: Int = 1): ByteArray {
        val pts = (0 until points).joinToString("") { i ->
            val t = startSecond + i * 5
            """<trkpt lat="${40.0 + i * 0.00018}" lon="-75.0"><ele>${100 + i}</ele><time>2026-05-0${day}T10:${"%02d".format(t / 60)}:${"%02d".format(t % 60)}Z</time></trkpt>"""
        }
        return """<?xml version="1.0"?><gpx version="1.1" creator="Watch"><trk><type>9</type><trkseg>$pts</trkseg></trk></gpx>""".toByteArray()
    }

    @Test
    fun theSameRunFromAnotherSourceIsSkippedUnlessMatchingIsOff() = runBlocking<Unit> {
        assertIs<ImportOutcome.Imported>(repo.import("strava.gpx", gpx()))
        // A second recording of the same run: starts 20 s later, a different file, almost the same distance.
        val other = repo.import("nike.gpx", gpx(startSecond = 20, points = 12))
        assertIs<ImportOutcome.SameRun>(other)
        assertEquals(SimilarKind.MOMENT, other.kind)
        assertEquals(1, repo.list(null, null, null).first().size)

        assertIs<ImportOutcome.Imported>(repo.import("nike.gpx", gpx(startSecond = 20, points = 12), match = MatchMode.OFF))
        assertEquals(2, repo.list(null, null, null).first().size)
    }

    @Test
    fun rebuildRestoresDataFromTheArchiveAndLeavesDeletedActivitiesDeleted() = runBlocking<Unit> {
        val kept = (repo.import("a.gpx", gpx()) as ImportOutcome.Imported).fileHash
        val gone = (repo.import("b.gpx", gpx(day = 2), match = MatchMode.OFF) as ImportOutcome.Imported).fileHash
        repo.delete(listOf(gone))
        File(dir, "streams/$kept.json.gz").delete()
        assertEquals(null, repo.streams(kept))

        val r = repo.rebuild(UntypedSport.UNKNOWN)
        assertEquals(1, r.rebuilt)
        assertEquals(1, r.skippedDeleted)
        assertTrue(r.failed.isEmpty())
        assertEquals(13, repo.streams(kept)!!.getValue("time").size)
        assertEquals(null, repo.detail(gone))
        assertEquals(1, repo.detail(kept)!!.activity.route.size)
    }

    @Test
    fun missingRoutesAreBuiltAgain() = runBlocking<Unit> {
        val hash = (repo.import("a.gpx", gpx()) as ImportOutcome.Imported).fileHash
        db.openHelper.writableDatabase.execSQL("DELETE FROM routes")
        assertEquals(0, repo.detail(hash)!!.activity.route.size)
        assertEquals(1, repo.ensureDerived())
        assertEquals(1, repo.detail(hash)!!.activity.route.size)
        assertEquals(0, repo.ensureDerived())             // nothing outdated now
    }

    @Test
    fun aBackupRestoresOntoAnEmptyPhone() = runBlocking<Unit> {
        repo.import("a.gpx", gpx())
        repo.import("b.gpx", gpx(day = 2), match = MatchMode.OFF)
        val runner = ImportRunner(repo, ctx.contentResolver)
        val zip = ByteArrayOutputStream()
        BackupManager(repo).export(zip, runner, 2)
        assertTrue(zip.size() > 0)
        assertEquals(2, runner.state.value.done)

        val dir2 = File(ctx.cacheDir, "bulk-flow-test-2").apply { deleteRecursively(); mkdirs() }
        val db2 = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        try {
            val repo2 = ActivityRepository(db2, dir2)
            val runner2 = ImportRunner(repo2, ctx.contentResolver)
            BackupManager(repo2).restore(ByteArrayInputStream(zip.toByteArray()), UntypedSport.UNKNOWN, runner2)
            assertEquals(2, runner2.state.value.imported)
            assertEquals(2, repo2.list(null, null, null).first().size)
            // Restoring again onto the same phone adds nothing.
            BackupManager(repo2).restore(ByteArrayInputStream(zip.toByteArray()), UntypedSport.UNKNOWN, runner2)
            assertEquals(2, runner2.state.value.duplicates)
            assertEquals(2, repo2.list(null, null, null).first().size)
        } finally {
            db2.close(); dir2.deleteRecursively()
        }
    }

    @Test
    fun upgradingFromVersionOneKeepsTheDataAndAddsTheImportLog() {
        val name = "migration-test"
        migrations.createDatabase(name, 1).apply {
            execSQL("INSERT INTO activities (file_hash, sport, start_time, duration, distance, elevation_gain, device, source_file, imported_at) " +
                "VALUES ('abc', 'running', '2026-05-01T10:00:00+00:00', 600.0, 2000.0, NULL, 'Watch', 'imports/abc_x.gpx', '2026-05-02T00:00:00+00:00')")
            close()
        }
        val db = migrations.runMigrationsAndValidate(name, 2, true, FitnessDatabase.MIGRATION_1_2)
        db.query("SELECT sport FROM activities WHERE file_hash = 'abc'").use { assertTrue(it.moveToFirst()); assertEquals("running", it.getString(0)) }
        db.execSQL("INSERT INTO import_log (doc_key, size, modified, outcome, file_hash, message, logged_at) VALUES ('k', 1, 2, 'imported', 'abc', NULL, 'now')")
        db.close()
    }
}
