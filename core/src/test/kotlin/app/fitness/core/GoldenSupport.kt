package app.fitness.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max

/** Locates and reads the fixtures the PC app exported (`tools/export_golden.py`). */
object Golden {
    val dir: File = File(System.getProperty("golden.dir") ?: error("golden.dir not set"))
    val available: Boolean get() = File(dir, "golden.json").exists()

    val root: JsonObject by lazy { Json.parseToJsonElement(File(dir, "golden.json").readText()).jsonObject }
    val perFile: List<JsonObject> by lazy { root.getValue("per_file").jsonArray.map { it.jsonObject } }

    fun inputBytes(name: String): ByteArray {
        val raw = File(dir, "inputs/$name").readBytes()
        return if (name.endsWith(".gz")) GZIPInputStream(raw.inputStream()).readBytes() else raw
    }
}

fun JsonElement?.num(): Double? = if (this == null || this is JsonNull) null else (this as JsonPrimitive).doubleOrNull
fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else this.jsonPrimitive.content
fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray

/** Relative comparison with an absolute floor, the tolerance rule from the parity plan. */
fun close(expected: Double?, actual: Double?, rel: Double = 1e-9, abs: Double = 1e-9): Boolean {
    if (expected == null || actual == null) return expected == null && actual == null
    return abs(expected - actual) <= max(abs, rel * max(abs(expected), abs(actual)))
}

/** Collects every mismatch so one run reports all of them, not only the first. */
class Mismatches {
    val items = mutableListOf<String>()
    fun check(label: String, ok: Boolean, detail: () -> String) {
        if (!ok) items += "$label: ${detail()}"
    }
}
