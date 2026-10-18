package app.fitness.android.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fitness.android.FitnessApp
import app.fitness.data.ImportState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Runs the long jobs (a whole folder, a rebuild from the archive, a restore) in the background as a foreground job with a
 * notification, so they carry on when the app is closed. WorkManager keeps the request, so a job killed by the system is
 * started again; the folder import then picks up where it left off from its import log, and the others are idempotent.
 */
class BulkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app get() = applicationContext as FitnessApp

    override suspend fun doWork(): Result {
        val mode = inputData.getString(MODE) ?: return Result.failure()
        val title = when (mode) {
            MODE_FOLDER -> "Importing a folder"; MODE_REBUILD -> "Rebuilding from the archive"
            MODE_ANALYTICS -> "Computing training load"; MODE_TERRAIN -> "Looking up terrain elevation"; else -> "Restoring a backup"
        }
        try { setForeground(info(title, null)) } catch (_: Exception) { /* not allowed in the background: carry on without the notification */ }
        return coroutineScope {
            val updates = launch {
                app.importRunner.state.sample(1000).collect { s ->
                    if (s.running) (applicationContext.getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification(s.title, s))
                }
            }
            try {
                val runner = app.importRunner
                val untyped = app.prefs.untyped.value
                when (mode) {
                    MODE_FOLDER -> {
                        val tree = Uri.parse(inputData.getString(URI) ?: return@coroutineScope Result.failure())
                        app.folderImporter.run(app.folderImporter.scan(tree), untyped, app.prefs.match.value, runner)
                    }
                    MODE_ANALYTICS -> {
                        runner.beginRun(title, 0)
                        app.analytics.refresh { done, total -> runner.progress(done, total) }
                        runner.dismiss()
                    }
                    MODE_TERRAIN -> {
                        runner.beginRun(title, 0)
                        val r = app.repository.fillTerrain(app.terrainService()) { done, total -> runner.progress(done, total) }
                        runner.note("Terrain elevation added to ${r.filled} activities" + if (r.failed > 0) "; ${r.failed} could not be done" + (r.firstError?.let { ": $it" } ?: "") else "")
                        runner.endRun()
                    }
                    MODE_REBUILD -> {
                        runner.beginRun(title, 0)
                        val r = app.repository.rebuild(untyped) { done, total -> runner.progress(done, total) }
                        r.failed.forEach { runner.fail(it) }
                        runner.note("Rebuilt ${r.rebuilt} activities" + if (r.skippedDeleted > 0) "; ${r.skippedDeleted} archived files belong to deleted activities and were left alone" else "")
                        runner.endRun()
                    }
                    else -> {
                        val uri = Uri.parse(inputData.getString(URI) ?: return@coroutineScope Result.failure())
                        val stream = applicationContext.contentResolver.openInputStream(uri)
                        if (stream == null) { runner.beginRun(title, 0); runner.fail("Could not open the backup"); runner.endRun() }
                        else stream.use { app.backup.restore(it, untyped, runner) }
                    }
                }
                if (mode != MODE_ANALYTICS && mode != MODE_TERRAIN) computeAnalytics(runner)
                Result.success()
            } finally {
                // A late progress update could re-post the ongoing notification after the system removed it.
                updates.cancelAndJoin()
                applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }
    }

    /** After an import, rebuild, or restore: metrics, bests and load for what is new. The import summary stays on screen. */
    private suspend fun computeAnalytics(runner: app.fitness.data.ImportRunner) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val todo = app.analytics.outdatedCount()
        if (todo == 0) { app.analytics.recomputeLoad(); return }
        val title = "Computing training load"
        try { setForeground(info(title, null)) } catch (_: Exception) { }
        app.analytics.refresh { done, total -> nm.notify(NOTIFICATION_ID, notification(title, ImportState(running = true, total = total, done = done))) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Working…", null)

    private fun info(title: String, s: ImportState?) =
        ForegroundInfo(NOTIFICATION_ID, notification(title, s), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(title: String, s: ImportState?): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Imports", NotificationManager.IMPORTANCE_LOW))
        val b = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true)
        if (s != null && s.total > 0) b.setProgress(s.total, s.done.coerceAtMost(s.total), false).setContentText("${s.done} of ${s.total}")
        else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val MODE = "mode"
        private const val URI = "uri"
        private const val MODE_FOLDER = "folder"
        private const val MODE_REBUILD = "rebuild"
        private const val MODE_RESTORE = "restore"
        private const val MODE_ANALYTICS = "analytics"
        private const val MODE_TERRAIN = "terrain"
        private const val CHANNEL = "imports"
        private const val NOTIFICATION_ID = 7
        private const val UNIQUE = "bulk-job"

        private fun enqueue(context: Context, data: Data) {
            val request = OneTimeWorkRequestBuilder<BulkWorker>().setInputData(data).build()
            // KEEP: a second request while one runs is ignored, so a double tap cannot start two imports.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }

        fun importFolder(context: Context, tree: Uri) =
            enqueue(context, Data.Builder().putString(MODE, MODE_FOLDER).putString(URI, tree.toString()).build())

        fun analytics(context: Context) = enqueue(context, Data.Builder().putString(MODE, MODE_ANALYTICS).build())

        fun terrain(context: Context) = enqueue(context, Data.Builder().putString(MODE, MODE_TERRAIN).build())

        fun rebuild(context: Context) = enqueue(context, Data.Builder().putString(MODE, MODE_REBUILD).build())

        fun restore(context: Context, backup: Uri) =
            enqueue(context, Data.Builder().putString(MODE, MODE_RESTORE).putString(URI, backup.toString()).build())

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE) }
    }
}
