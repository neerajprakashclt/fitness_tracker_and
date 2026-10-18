package app.fitness.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * Phase 0 spike: run the Garmin SDK over every FIT file in the staged history, to find files it rejects that
 * fitparse accepted (the PC imported all of them). Prints a summary; does not fail, it is a measurement.
 */
class FitCorpusTest {
    @Test
    fun parsesTheWholeStagedHistory() {
        val dir = File(System.getProperty("seed.dir") ?: return, "imports")
        if (!dir.isDirectory) { println("seed/imports missing: skipped"); return }
        val files = dir.listFiles { f -> f.name.removeSuffix(".gz").endsWith(".fit") }!!.sortedBy { it.name }
        val failures = mutableListOf<String>()
        var samples = 0L
        val started = System.nanoTime()
        for (f in files) {
            try {
                val bytes = f.readBytes().let { if (f.name.endsWith(".gz")) GZIPInputStream(it.inputStream()).readBytes() else it }
                samples += FitImporter.parse(bytes).streams["time"]?.size ?: 0
            } catch (e: Throwable) {
                failures += "${f.name}: ${e::class.simpleName}: ${e.message?.take(100)}"
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("FIT corpus: ${files.size} files, ${files.size - failures.size} parsed, ${failures.size} failed, " +
            "$samples samples in ${"%.1f".format(seconds)} s (${"%.0f".format(files.size / seconds)} files/s)")
        failures.take(25).forEach { println("  FAILED $it") }
        failures.groupingBy { it.substringAfter(": ").substringBefore(":") + ": " + it.substringAfterLast(": ").take(40) }
            .eachCount().entries.sortedByDescending { it.value }.take(8).forEach { println("  reason x${it.value}: ${it.key}") }
    }
}
