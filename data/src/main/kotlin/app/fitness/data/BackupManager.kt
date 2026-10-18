package app.fitness.data

import app.fitness.core.MatchMode
import app.fitness.core.Times
import app.fitness.core.UntypedSport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore. The database is derived from the raw files, so a backup is a zip of the archived originals
 * (`imports/<name>`) plus a small `meta.json`; restoring imports each file again through the normal path, which is
 * idempotent, so restoring onto a phone that already has some activities only adds what is missing. Deleted activities stay
 * deleted only if their files are no longer in the archive; a backup made before a delete brings them back.
 */
class BackupManager(private val repo: ActivityRepository) {
    suspend fun export(out: OutputStream, runner: ImportRunner, activityCount: Int) = withContext(Dispatchers.IO) {
        val files = repo.archiveFiles()
        runner.beginRun("Backing up", files.size)
        try {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)            // the originals are mostly gzipped already
                zip.putNextEntry(ZipEntry("meta.json"))
                zip.write("""{"app":"fitness-tracker-android","version":1,"exported_at":"${Times.format(Instant.now())}","files":${files.size},"activities":$activityCount}"""
                    .toByteArray())
                zip.closeEntry()
                for ((i, f) in files.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("imports/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (i % 50 == 0) runner.progress(i + 1, files.size)
                }
            }
            runner.progress(files.size, files.size)
            runner.endRun()
        } catch (e: kotlinx.coroutines.CancellationException) {
            runner.endRun(cancelled = true); throw e
        } catch (e: Exception) {
            runner.fail("Backup failed: ${e.message}"); runner.endRun()
        }
    }

    suspend fun restore(input: InputStream, untyped: UntypedSport, runner: ImportRunner) = withContext(Dispatchers.IO) {
        runner.beginRun("Restoring a backup", 0)
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith("imports/")) continue
                    val name = entry.name.removePrefix("imports/")
                    runner.progress(runner.state.value.done, runner.state.value.total + 1)
                    runner.record(name, repo.import(name, zip.readBytes(), untyped, MatchMode.OFF))
                }
            }
            runner.endRun()
        } catch (e: kotlinx.coroutines.CancellationException) {
            runner.endRun(cancelled = true); throw e
        } catch (e: Exception) {
            runner.fail("Restore failed: ${e.message}"); runner.endRun()
        }
    }
}
