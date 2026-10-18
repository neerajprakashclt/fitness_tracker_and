package app.fitness.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.fitness.core.MatchMode
import app.fitness.core.UntypedSport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of the current run (an import, a rebuild, a restore), and the result of the last one. */
data class ImportState(
    val running: Boolean = false,
    /** What is going on, e.g. "Importing", "Rebuilding from the archive". */
    val title: String = "Importing",
    val total: Int = 0,
    val done: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    /** Skipped as the same run from another source. */
    val sameRun: Int = 0,
    /** Skipped without being opened because an earlier run of the same folder had dealt with them. */
    val seenBefore: Int = 0,
    val current: String? = null,
    val failed: List<String> = emptyList(),
    /** One line per file skipped as the same run, so the user can check them. */
    val notes: List<String> = emptyList(),
    /** Set once a run has finished; cleared by [ImportRunner.dismiss]. */
    val finished: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Tracks the current run for the screen and queues files the user picked or shared. Runs the picked files in an
 * application-scoped coroutine so they survive screen rotation and navigation, one at a time and never on the main
 * thread. Long jobs (a whole folder, a rebuild, a restore) run in a background worker and report through the same state.
 * A bad file is recorded and skipped, never fatal.
 */
class ImportRunner(private val repo: ActivityRepository, private val resolver: ContentResolver) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state

    /** How the files picked or shared are treated; the app keeps these in step with Settings. */
    @Volatile var untyped: UntypedSport = UntypedSport.GUESS_FOOT
    @Volatile var match: MatchMode = MatchMode.MOMENT

    private val queue = Channel<Uri>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (uri in queue) {
                importOne(uri)
                _state.update { if (it.done >= it.total) it.copy(running = false, finished = true, current = null) else it }
            }
        }
    }

    /** Queue files; a run already in progress keeps going and the new files join its total. */
    fun start(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { if (it.running) it.copy(total = it.total + uris.size) else ImportState(running = true, total = uris.size) }
        uris.forEach { queue.trySend(it) }
    }

    private suspend fun importOne(uri: Uri) {
        val name = displayName(uri)
        val outcome = try {
            val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) ImportOutcome.Failed("Could not open $name") else repo.import(name, bytes, untyped, match)
        } catch (e: Exception) {
            ImportOutcome.Failed("Could not read $name: ${e.message}")
        }
        record(name, outcome)
    }

    // The three calls below are for long jobs that run elsewhere (a worker) and report through the same state.

    fun beginRun(title: String, total: Int) {
        _state.value = ImportState(running = true, title = title, total = total)
    }

    /** Count one file's outcome. [outcome] null means it was skipped because an earlier run had already dealt with it. */
    fun record(name: String, outcome: ImportOutcome?) {
        _state.update {
            it.copy(
                done = it.done + 1,
                current = name,
                imported = it.imported + if (outcome is ImportOutcome.Imported) 1 else 0,
                duplicates = it.duplicates + if (outcome is ImportOutcome.Duplicate) 1 else 0,
                sameRun = it.sameRun + if (outcome is ImportOutcome.SameRun) 1 else 0,
                seenBefore = it.seenBefore + if (outcome == null) 1 else 0,
                failed = if (outcome is ImportOutcome.Failed) it.failed + "$name: ${outcome.reason}" else it.failed,
                notes = if (outcome is ImportOutcome.SameRun)
                    it.notes + "$name: same run as an activity already stored (${outcome.kind.name.lowercase()}, ${outcome.offsetSeconds.toLong()} s apart)" else it.notes,
            )
        }
    }

    fun note(text: String) = _state.update { it.copy(notes = it.notes + text) }

    fun fail(text: String) = _state.update { it.copy(failed = it.failed + text) }

    fun progress(done: Int, total: Int) = _state.update { it.copy(done = done, total = total) }

    fun endRun(cancelled: Boolean = false) = _state.update { it.copy(running = false, finished = true, current = null, cancelled = cancelled) }

    fun dismiss() = _state.update { ImportState() }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "file"
    }
}
