package app.fitness.core

import java.io.File
import kotlin.test.Test

/**
 * Runs the whole import pipeline over every file in the staged history (`seed/imports`) and prints a summary: how many
 * parse, how many are byte-identical duplicates, the sports found, and why any file failed. A measurement, not a gate:
 * the PC imported all of these, so every failure here is something to look at.
 */
class ImportCorpusTest {
    @Test
    fun importsTheWholeStagedHistory() {
        val dir = File(System.getProperty("seed.dir") ?: return, "imports")
        if (!dir.isDirectory) { println("seed/imports missing: skipped"); return }
        val files = dir.listFiles()!!.sortedBy { it.name }
        val failures = mutableListOf<String>()
        val hashes = HashSet<String>()
        val sports = sortedMapOf<String, Int>()
        val formats = sortedMapOf<String, Int>()
        var duplicates = 0
        val started = System.nanoTime()
        for (f in files) {
            try {
                val decoded = Importer.decode(f.name, f.readBytes())
                if (!hashes.add(decoded.fileHash)) { duplicates++; continue }
                val a = Importer.parse(decoded, f.name)
                formats.merge(decoded.format, 1, Int::plus)
                sports.merge(a.sport, 1, Int::plus)
            } catch (e: Throwable) {
                failures += "${f.name}: ${e::class.simpleName}: ${e.message?.take(120)}"
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("corpus: ${files.size} files, ${hashes.size - failures.size} parsed, $duplicates duplicates, ${failures.size} failed, " +
            "in ${"%.1f".format(seconds)} s; formats $formats")
        println("sports: $sports")
        failures.take(30).forEach { println("  FAILED $it") }
    }
}
