package app.fitness.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import app.fitness.core.Importer
import app.fitness.core.MatchMode
import app.fitness.core.StravaCsv
import app.fitness.core.Times
import app.fitness.core.UntypedSport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant

/** A file found in the chosen folder. */
class TreeFile(val docId: String, val uri: Uri, val relativePath: String, val name: String, val size: Long, val modified: Long)

/** What a scan of the folder found, before anything is imported. */
class FolderScan(
    val tree: Uri, val files: List<TreeFile>, val byFormat: Map<String, Int>, val seenBefore: Int,
    /** Strava's `activities.csv`, when the folder is an export's top folder: it carries the climb some watches never recorded. */
    val csv: Uri? = null,
) {
    val newFiles get() = files.size - seenBefore
}

/**
 * Imports every activity file under a folder the user picked (PC: `import_dir`): subfolders included, `.fit`, `.gpx`, `.tcx`
 * and their `.gz` versions, in the order the PC uses (by the leading activity number in each name). One bad file never stops
 * the run. Every file seen is written to the import log, so a run that is stopped or killed carries on where it left off,
 * and running the same folder again only looks at new files. A Strava export keeps recordings in `activities/`; its sibling
 * folders hold nothing importable and are skipped.
 */
class FolderImporter(private val resolver: ContentResolver, private val repo: ActivityRepository) {
    private val dao get() = repo.dao()

    private fun key(tree: Uri, docId: String) = "$tree|$docId"

    private class Entry(val docId: String, val name: String, val mime: String?, val size: Long, val modified: Long)

    private fun children(tree: Uri, parentDocId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Entry>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Entry(c.getString(0), c.getString(1) ?: "", c.getString(2), if (c.isNull(3)) -1 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
        }
        return out
    }

    private fun isDir(e: Entry) = e.mime == DocumentsContract.Document.MIME_TYPE_DIR

    private fun isActivityFile(name: String) = Importer.extension(name).first in Importer.FORMATS

    /** The PC walks files in order of the number a Strava export puts at the start of each name; archive names add a hash prefix. */
    private fun activityId(name: String): Long =
        Regex("^[0-9]+").find(name.replace(Regex("^[0-9a-f]{12}_"), ""))?.value?.toLongOrNull() ?: -1

    /** List the activity files under [tree] and say how many an earlier run has already dealt with. */
    suspend fun scan(tree: Uri): FolderScan = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootChildren = children(tree, rootId)
        // A Strava export: only activities/ holds recordings.
        val start = rootChildren.firstOrNull { isDir(it) && it.name == "activities" }
        val found = ArrayList<TreeFile>()
        fun walk(docId: String, path: String, entries: List<Entry>) {
            for (e in entries) {
                if (isDir(e)) walk(e.docId, "$path${e.name}/", children(tree, e.docId))
                else if (isActivityFile(e.name)) found += TreeFile(e.docId, DocumentsContract.buildDocumentUriUsingTree(tree, e.docId), "$path${e.name}", e.name, e.size, e.modified)
            }
        }
        if (start != null) walk(start.docId, "activities/", children(tree, start.docId)) else walk(rootId, "", rootChildren)
        val files = found.sortedWith(compareBy({ activityId(it.name) }, { it.name.replace(Regex("^[0-9a-f]{12}_"), "") }))

        val prefix = "$tree|"
        val done = dao.logFor(prefix, prefix.length).filter { it.outcome != "failed" }.associate { it.docKey to it }
        val seen = files.count { f -> done[key(tree, f.docId)]?.let { it.size == f.size && it.modified == f.modified } == true }
        val csv = rootChildren.firstOrNull { !isDir(it) && it.name.equals(StravaCsv.NAME, ignoreCase = true) }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.docId) }
        FolderScan(tree, files, files.groupingBy { Importer.extension(it.name).first }.eachCount().toSortedMap(), seen, csv)
    }

    /**
     * Import the files of [scan], reporting through [runner]. Safe to call again after a kill: files already logged are
     * counted as seen and not opened.
     */
    suspend fun run(scan: FolderScan, untyped: UntypedSport, match: MatchMode, runner: ImportRunner, title: String = "Importing a folder") {
        val tree = scan.tree
        val prefix = "$tree|"
        val logged = dao.logFor(prefix, prefix.length).filter { it.outcome != "failed" }.associateBy { it.docKey }
        runner.beginRun(title, scan.files.size)
        val pending = ArrayList<ImportLogEntity>()
        suspend fun flush() { if (pending.isNotEmpty()) { dao.putLog(pending.toList()); pending.clear() } }
        try {
            for (f in scan.files) {
                currentCoroutineContext().ensureActive()
                val k = key(tree, f.docId)
                val prior = logged[k]
                if (prior != null && prior.size == f.size && prior.modified == f.modified) {
                    runner.record(f.name, null)
                    continue
                }
                val outcome = try {
                    val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(f.uri)?.use { it.readBytes() } }
                    if (bytes == null) ImportOutcome.Failed("Could not open ${f.relativePath}") else repo.import(f.name, bytes, untyped, match)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ImportOutcome.Failed("${f.relativePath}: ${e.javaClass.simpleName}: ${e.message}")
                }
                runner.record(f.relativePath, outcome)
                pending += ImportLogEntity(
                    k, f.size, f.modified,
                    when (outcome) { is ImportOutcome.Imported -> "imported"; is ImportOutcome.Duplicate -> "duplicate"; is ImportOutcome.SameRun -> "similar"; is ImportOutcome.Failed -> "failed" },
                    (outcome as? ImportOutcome.Imported)?.fileHash ?: (outcome as? ImportOutcome.Duplicate)?.fileHash,
                    (outcome as? ImportOutcome.Failed)?.reason, Times.format(Instant.now()),
                )
                if (pending.size >= 50) flush()
            }
            // A Strava export brings its own climb: fill what the files lack, now that every file is in.
            scan.csv?.let { csv ->
                try {
                    val text = withContext(Dispatchers.IO) { resolver.openInputStream(csv)?.use { it.readBytes().decodeToString() } }
                    if (text != null) {
                        val n = repo.applyStravaCsv(StravaCsv.elevationGains(text))
                        if (n > 0) runner.note("Elevation from Strava's activities.csv added to $n activities")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runner.note("Could not read activities.csv: ${e.message}")
                }
            }
            runner.endRun()
        } catch (e: kotlinx.coroutines.CancellationException) {
            runner.endRun(cancelled = true)
            throw e
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }
}
