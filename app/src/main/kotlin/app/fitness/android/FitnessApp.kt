package app.fitness.android

import android.app.Application
import android.net.Uri
import androidx.work.Configuration
import app.fitness.android.ui.Prefs
import app.fitness.android.work.BulkWorker
import app.fitness.data.ActivityRepository
import app.fitness.data.AnalyticsRepository
import app.fitness.data.BackupManager
import app.fitness.data.FitnessDatabase
import app.fitness.data.FolderImporter
import app.fitness.data.FolderScan
import app.fitness.data.LogRepository
import app.fitness.data.TerrainTiles
import app.fitness.core.StravaCsv
import app.fitness.data.ImportRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** What the user is shown after choosing a folder, before anything is imported. */
sealed interface FolderPreview {
    data object Scanning : FolderPreview
    class Ready(val scan: FolderScan) : FolderPreview
    class Failed(val reason: String) : FolderPreview
}

/** Hand-wired dependencies: one database, one repository, one import runner, for the life of the process. */
class FitnessApp : Application(), Configuration.Provider {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs by lazy { Prefs(this) }
    private val database by lazy { FitnessDatabase.create(this) }
    val repository by lazy { ActivityRepository(database, filesDir) }
    val analytics by lazy { AnalyticsRepository(database, repository) }
    val log by lazy { LogRepository(database, repository) }
    val importRunner by lazy { ImportRunner(repository, contentResolver) }
    val folderImporter by lazy { FolderImporter(contentResolver, repository) }
    val backup by lazy { BackupManager(repository) }
    val databaseFile: File get() = getDatabasePath("fitness.db")

    private val _folderPreview = MutableStateFlow<FolderPreview?>(null)
    val folderPreview: StateFlow<FolderPreview?> = _folderPreview

    /** Tiles are only a download away, so the system may clear them when it needs the space. */
    fun terrainService() = TerrainTiles(File(cacheDir, "terrain"))

    private val _elevationMessage = MutableStateFlow<String?>(null)
    val elevationMessage: StateFlow<String?> = _elevationMessage

    fun dismissElevationMessage() { _elevationMessage.value = null }

    /** Fill climb from a Strava `activities.csv` the user picked, for activities whose files have none. */
    fun applyStravaCsv(uri: Uri) {
        appScope.launch {
            _elevationMessage.value = try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Could not open the file")
                val gains = StravaCsv.elevationGains(text)
                val n = repository.applyStravaCsv(gains)
                if (n > 0) "Elevation added to $n activities from ${gains.size} rows with a climb."
                else "Nothing to add: ${gains.size} rows with a climb, but none matches an activity that lacks elevation."
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
        }
    }

    fun startTerrainFill() = BulkWorker.terrain(this)

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Files picked or shared follow the Settings choices.
        appScope.launch { prefs.untyped.collect { importRunner.untyped = it } }
        appScope.launch { prefs.match.collect { importRunner.match = it } }
        // Turning the option off and on again also lets the look-up try again after it gave up.
        appScope.launch { prefs.terrain.collect { repository.terrain = if (it) terrainService() else null } }
        // Derived data is rebuilt when missing or built by older logic. The analytics for a whole history take a minute or
        // two, so they run as a background job; with nothing outdated only the day-by-day series is brought up to today.
        repository.afterDelete = { analytics.recomputeLoad() }
        importRunner.afterPicked = { analytics.refresh() }
        appScope.launch {
            repository.ensureDerived()
            if (analytics.outdatedCount() > 100) BulkWorker.analytics(this@FitnessApp) else analytics.refresh()
        }
    }

    /** List the folder the user chose and show what is in it; nothing is imported until they confirm. */
    fun previewFolder(tree: Uri) {
        _folderPreview.value = FolderPreview.Scanning
        appScope.launch {
            _folderPreview.value = try { FolderPreview.Ready(folderImporter.scan(tree)) }
            catch (e: Exception) { FolderPreview.Failed(e.message ?: e.javaClass.simpleName) }
        }
    }

    fun confirmFolder() {
        val ready = _folderPreview.value as? FolderPreview.Ready ?: return
        _folderPreview.value = null
        BulkWorker.importFolder(this, ready.scan.tree)
    }

    fun dismissFolderPreview() { _folderPreview.value = null }

    fun startRebuild() = BulkWorker.rebuild(this)
    fun startRestore(backup: Uri) = BulkWorker.restore(this, backup)
    fun cancelJob() = BulkWorker.cancel(this)

    fun exportBackup(out: Uri) {
        appScope.launch {
            val stream = contentResolver.openOutputStream(out) ?: return@launch
            val count = repository.count().first()
            stream.use { backup.export(it, importRunner, count) }
        }
    }

    fun forgetImportHistory() { appScope.launch { repository.clearImportLog() } }
}
