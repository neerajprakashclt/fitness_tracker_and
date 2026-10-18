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

    @Test
    fun upgradingToVersionThreeAddsTheAnalyticsTables() {
        val name = "migration-test-3"
        migrations.createDatabase(name, 2).close()
        val db = migrations.runMigrationsAndValidate(name, 3, true, FitnessDatabase.MIGRATION_2_3)
        db.execSQL("INSERT INTO settings (`key`, value, updated_at) VALUES ('ftp', 250.0, 'now')")
        db.close()
    }

    @Test
    fun upgradingToVersionFourAddsTheRunningProfile() {
        val name = "migration-test-4"
        migrations.createDatabase(name, 3).close()
        val db = migrations.runMigrationsAndValidate(name, 4, true, FitnessDatabase.MIGRATION_3_4)
        db.query("SELECT speed_hist, reps FROM activity_metrics").use { assertEquals(0, it.count) }
        db.close()
    }

    @Test
    fun upgradingToVersionFiveAddsEfficiency() {
        val name = "migration-test-5"
        migrations.createDatabase(name, 4).close()
        val db = migrations.runMigrationsAndValidate(name, 5, true, FitnessDatabase.MIGRATION_4_5)
        db.query("SELECT efficiency, efficiency_basis, decoupling FROM activity_metrics").use { assertEquals(0, it.count) }
        db.close()
    }

    @Test
    fun upgradingToVersionSixAddsGoals() {
        val name = "migration-test-6"
        migrations.createDatabase(name, 5).close()
        val db = migrations.runMigrationsAndValidate(name, 6, true, FitnessDatabase.MIGRATION_5_6)
        db.execSQL("INSERT INTO goals (id, sport, metric, period, target, created_at) VALUES ('g', NULL, 'distance', 'month', 1000.0, 'now')")
        db.close()
    }

    @Test
    fun goalsAreStoredLoggedAndProgressFollowsTheActivities() = runBlocking<Unit> {
        val log = LogRepository(db, repo)
        repo.import("run.gpx", gpx(points = 60))
        log.addGoal("running", app.fitness.core.LogMetric.ACTIVITIES, app.fitness.core.Period.YEAR, 5.0)
        val goals = log.goals().first()
        assertEquals(1, goals.size)
        assertEquals("running", goals[0].sport)
        assertEquals(1, repo.dao().changes().count { it.entity == "goal" && it.op == "put" })
        log.deleteGoal(goals[0].id)
        assertTrue(log.goals().first().isEmpty())
        assertEquals(1, repo.dao().changes().count { it.entity == "goal" && it.op == "delete" })
        assertEquals(1, log.rows().first().size)
    }

    private fun gpxNoElevation(day: Int = 1) = String(gpx(day = day)).replace(Regex("<ele>[0-9]+</ele>"), "").toByteArray()

    /** Heights that rise 20 m over the whole track, whatever the track is. */
    private class Slope : TerrainService {
        var calls = 0
        override val provider = "test"
        override suspend fun lookup(lat: DoubleArray, lon: DoubleArray): app.fitness.core.TerrainProfile {
            calls++
            return app.fitness.core.TerrainProfile(DoubleArray(lat.size) { 20.0 * it / (lat.size - 1) }, 20.0)
        }
    }

    private class Down : TerrainService {
        override val provider = "test"
        override suspend fun lookup(lat: DoubleArray, lon: DoubleArray): app.fitness.core.TerrainProfile = throw app.fitness.core.TerrainException("could not reach the host")
    }

    @Test
    fun aFileWithItsOwnElevationIsNeverTouchedAndOneWithoutGetsTerrainClimbThatSurvivesARebuild() = runBlocking<Unit> {
        val slope = Slope()
        repo.terrain = slope
        val own = (repo.import("own.gpx", gpx(day = 1)) as ImportOutcome.Imported)
        val bare = (repo.import("bare.gpx", gpxNoElevation(day = 2)) as ImportOutcome.Imported)
        assertEquals(1, slope.calls)                                    // only the file with no altitude was looked up
        assertEquals(null, repo.elevationSource(own.fileHash))
        assertEquals("terrain data", repo.elevationSource(bare.fileHash))
        assertEquals(20.0, repo.detail(bare.fileHash)!!.activity.elevationGain)
        assertTrue(repo.streams(bare.fileHash)!!.containsKey("altitude"))

        repo.rebuild(UntypedSport.UNKNOWN)                              // the original has no altitude: the stored climb comes back
        assertEquals(20.0, repo.detail(bare.fileHash)!!.activity.elevationGain)
        assertTrue(repo.streams(bare.fileHash)!!.containsKey("altitude"))
        assertEquals(1, slope.calls)                                    // and without asking the source again
    }

    @Test
    fun aTerrainFailureNeverFailsTheImport() = runBlocking<Unit> {
        repo.terrain = Down()
        val outcome = repo.import("bare.gpx", gpxNoElevation())
        assertIs<ImportOutcome.Imported>(outcome)
        assertTrue(outcome.note!!.contains("could not reach the host"))
        assertEquals(null, repo.detail(outcome.fileHash)!!.activity.elevationGain)
    }

    @Test
    fun noTerrainServiceMeansNoLookup() = runBlocking<Unit> {
        val outcome = repo.import("bare.gpx", gpxNoElevation()) as ImportOutcome.Imported
        assertEquals(null, outcome.note)
        assertEquals(null, repo.detail(outcome.fileHash)!!.activity.elevationGain)
    }

    @Test
    fun stravaCsvFillsGapsOnlyAndASecondRunAddsNothing() = runBlocking<Unit> {
        val bare = (repo.import("123.gpx", gpxNoElevation(day = 1)) as ImportOutcome.Imported).fileHash
        val own = (repo.import("456.gpx", gpx(day = 2)) as ImportOutcome.Imported).fileHash
        val ownGain = repo.detail(own)!!.activity.elevationGain
        val csv = "Activity ID,Filename,Elevation Gain\n1,activities/123.gpx,56.0\n2,activities/456.gpx,999.0\n3,activities/other.gpx,5.0\n"
        val gains = app.fitness.core.StravaCsv.elevationGains(csv)
        assertEquals(1, repo.applyStravaCsv(gains))                     // the file with its own elevation keeps it
        assertEquals(56.0, repo.detail(bare)!!.activity.elevationGain)
        assertEquals(ownGain, repo.detail(own)!!.activity.elevationGain)
        assertEquals("Strava", repo.elevationSource(bare))
        assertEquals(0, repo.applyStravaCsv(gains))
        repo.rebuild(UntypedSport.UNKNOWN)
        assertEquals(56.0, repo.detail(bare)!!.activity.elevationGain)  // a rebuild puts it back
    }

    @Test
    fun theClimbFromOutsideTheFilesSurvivesABackupRoundTrip() = runBlocking<Unit> {
        repo.terrain = Slope()
        val terrainOne = (repo.import("t.gpx", gpxNoElevation(day = 1)) as ImportOutcome.Imported).fileHash
        repo.terrain = null
        val csvOne = (repo.import("123.gpx", gpxNoElevation(day = 2)) as ImportOutcome.Imported).fileHash
        repo.applyStravaCsv(mapOf("123.gpx" to 56.0))
        val zip = ByteArrayOutputStream()
        BackupManager(repo).export(zip, ImportRunner(repo, ctx.contentResolver), 2)

        val dir2 = File(ctx.cacheDir, "bulk-flow-test-3").apply { deleteRecursively(); mkdirs() }
        val db2 = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        try {
            val repo2 = ActivityRepository(db2, dir2)
            BackupManager(repo2).restore(ByteArrayInputStream(zip.toByteArray()), UntypedSport.UNKNOWN, ImportRunner(repo2, ctx.contentResolver))
            assertEquals(20.0, repo2.detail(terrainOne)!!.activity.elevationGain)
            assertEquals(56.0, repo2.detail(csvOne)!!.activity.elevationGain)
            assertTrue(repo2.streams(terrainOne)!!.containsKey("altitude"))
        } finally {
            db2.close(); dir2.deleteRecursively()
        }
    }

    @Test
    fun theOneOffTerrainFillOverExistingActivitiesFillsOnlyThoseWithATrackAndNoClimb() = runBlocking<Unit> {
        val bare = (repo.import("bare.gpx", gpxNoElevation(day = 1)) as ImportOutcome.Imported).fileHash
        val own = (repo.import("own.gpx", gpx(day = 2)) as ImportOutcome.Imported).fileHash
        assertEquals(1, repo.missingElevationCount().first())
        val r = repo.fillTerrain(Slope())
        assertEquals(1, r.filled)
        assertEquals(0, repo.missingElevationCount().first())
        assertEquals(20.0, repo.detail(bare)!!.activity.elevationGain)
        assertTrue(repo.detail(own)!!.activity.elevationGain != 20.0)
        val down = repo.import("bare2.gpx", gpxNoElevation(day = 3)) as ImportOutcome.Imported
        val failed = repo.fillTerrain(Down())
        assertEquals(0, failed.filled)
        assertEquals(1, failed.failed)
        assertEquals("could not reach the host", failed.firstError)
        assertEquals(null, repo.detail(down.fileHash)!!.activity.elevationGain)
    }

    @Test
    fun upgradingToVersionSevenAddsTheElevationTables() {
        val name = "migration-test-7"
        migrations.createDatabase(name, 6).close()
        val db = migrations.runMigrationsAndValidate(name, 7, true, FitnessDatabase.MIGRATION_6_7)
        db.execSQL("INSERT INTO elevation_overrides (file_hash, elevation_gain, source) VALUES ('h', 5.0, 'strava_csv')")
        db.execSQL("INSERT INTO terrain_elevation (file_hash, provider, elevation_gain, altitude) VALUES ('h', 'terrarium', 5.0, '[1.0,2.0]')")
        db.close()
    }

    @Test
    fun analyticsAreComputedFromTheStreamsAndFollowTheThresholds() = runBlocking<Unit> {
        val analytics = AnalyticsRepository(db, repo)
        val hash = (repo.import("run.gpx", gpx(points = 60)) as ImportOutcome.Imported).fileHash
        assertEquals(1, analytics.outdatedCount())
        analytics.refresh()
        assertEquals(0, analytics.outdatedCount())
        val info = analytics.loadOf(hash)!!
        assertTrue(info.load > 0)
        assertTrue(analytics.daily().first().isNotEmpty())
        // A foot activity with no heart rate is scored at a flat rate per hour, so thresholds do not change it.
        assertEquals("duration", info.method)
        analytics.saveSetting("max_hr", 190.0)
        assertEquals(190.0, analytics.settings().first().maxHr.value)
        analytics.saveSetting("max_hr", null)
        assertEquals(null, analytics.settings().first().maxHr.source) // cleared, and no heart-rate data to estimate from
        assertTrue(analytics.curveSports().contains("running"))
        // Deleting the activity removes its derived rows; the load series follows.
        repo.afterDelete = { analytics.recomputeLoad() }
        repo.delete(listOf(hash))
        assertTrue(analytics.daily().first().isEmpty())
    }
}
