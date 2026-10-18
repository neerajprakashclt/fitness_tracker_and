package app.fitness.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The on-disk form of an activity's streams, `streams/<file_hash>.json.gz`: the same JSON object the PC app stores in its
 * `streams` table (`{"time": [...], "heart_rate": [...]}`, `null` for a missing sample), gzipped.
 */
object StreamCodec {
    fun encode(streams: Map<String, DoubleArray>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter().use { w ->
            w.write('{'.code)
            var firstKey = true
            for (key in StreamKeys.ALL) {
                val values = streams[key] ?: continue
                if (!firstKey) w.write(','.code)
                firstKey = false
                w.write("\"$key\":[")
                for (i in values.indices) {
                    if (i > 0) w.write(','.code)
                    val v = values[i]
                    if (v.isNaN()) w.write("null") else w.write(v.toString())
                }
                w.write(']'.code)
            }
            w.write('}'.code)
        }
        return out.toByteArray()
    }

    fun decode(gz: ByteArray): Map<String, DoubleArray> {
        val text = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes().decodeToString() }
        val root = Json.parseToJsonElement(text).jsonObject
        return root.mapValues { (_, v) ->
            val arr = v as JsonArray
            DoubleArray(arr.size) { i ->
                val e = arr[i]
                if (e is JsonNull) Double.NaN else (e as JsonPrimitive).doubleOrNull ?: Double.NaN
            }
        }
    }
}
