package app.fitness.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of the current import run, and the result of the last one. */
data class ImportState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val failed: List<String> = emptyList(),
    /** Set once a run has finished; cleared by [ImportRunner.dismiss]. */
    val finished: Boolean = false,
)

/**
 * Imports the files the user picked or shared. Runs in an application-scoped coroutine so it survives screen rotation
 * and navigation, one file at a time and never on the main thread. A bad file is recorded and skipped, never fatal.
 */
class ImportRunner(private val repo: ActivityRepository, private val resolver: ContentResolver) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state

    private val queue = Channel<Pair<Uri, UntypedSport>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((uri, untyped) in queue) {
                importOne(uri, untyped)
                _state.update { if (it.done >= it.total) it.copy(running = false, finished = true) else it }
            }
        }
    }

    /** Queue files; a run already in progress keeps going and the new files join its total. */
    fun start(uris: List<Uri>, untyped: UntypedSport) {
        if (uris.isEmpty()) return
        _state.update { if (it.running) it.copy(total = it.total + uris.size) else ImportState(running = true, total = uris.size) }
        uris.forEach { queue.trySend(it to untyped) }
    }

    private suspend fun importOne(uri: Uri, untyped: UntypedSport) {
        val name = displayName(uri)
        val outcome = try {
            val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) ImportOutcome.Failed("Could not open $name") else repo.import(name, bytes, untyped)
        } catch (e: Exception) {
            ImportOutcome.Failed("Could not read $name: ${e.message}")
        }
        _state.update {
            it.copy(
                done = it.done + 1,
                imported = it.imported + if (outcome is ImportOutcome.Imported) 1 else 0,
                duplicates = it.duplicates + if (outcome is ImportOutcome.Duplicate) 1 else 0,
                failed = if (outcome is ImportOutcome.Failed) it.failed + "$name: ${outcome.reason}" else it.failed,
            )
        }
    }

    fun dismiss() = _state.update { ImportState() }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "file"
    }
}
