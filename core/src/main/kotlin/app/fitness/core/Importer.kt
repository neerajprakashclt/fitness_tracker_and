package app.fitness.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** A file that cannot be imported (unsupported, corrupt, ...). The message is meant to be shown to the user. */
class ImportFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A file read into memory: the decompressed payload and its identity. Port of `app/importers/__init__.py`. */
class DecodedFile(val format: String, val payload: ByteArray, val fileHash: String)

object Importer {
    val FORMATS = setOf("fit", "gpx", "tcx")

    /** The format extension without the dot ("fit", "gpx", "tcx") and whether the file is gzipped. */
    fun extension(filename: String): Pair<String, Boolean> {
        var name = filename.lowercase()
        val gz = name.endsWith(".gz")
        if (gz) name = name.removeSuffix(".gz")
        val dot = name.lastIndexOf('.')
        return (if (dot != -1) name.substring(dot + 1) else "") to gz
    }

    /** Characters outside this set become `_`, and only the last 100 are kept (PC: `_safe_name`). */
    fun safeName(filename: String): String = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(100)

    /** The archive name of an imported file, `<first 12 of hash>_<safe name>` (PC: `data/imports/`). */
    fun archiveName(fileHash: String, filename: String): String = "${fileHash.take(12)}_${safeName(filename)}"

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Decompress and identify a file. The hash is of the *decompressed* bytes, so `x.fit` and `x.fit.gz` are one
     * activity, and equals the PC app's `file_hash`.
     */
    fun decode(filename: String, data: ByteArray): DecodedFile {
        val (format, gz) = extension(filename)
        if (format !in FORMATS) throw ImportFailed("Unsupported file type: $filename")
        val payload = try {
            if (gz) GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() } else data
        } catch (e: IOException) {
            throw ImportFailed("Could not decompress $filename: ${e.message}", e)
        }
        return DecodedFile(format, payload, sha256(payload))
    }

    fun parse(file: DecodedFile, filename: String): ParsedActivity = try {
        when (file.format) {
            "fit" -> FitImporter.parse(file.payload)
            "gpx" -> GpxImporter.parse(file.payload)
            else -> TcxImporter.parse(file.payload)
        }
    } catch (e: Exception) {
        throw ImportFailed("Could not parse $filename: ${e.message}", e)
    }

    fun parse(filename: String, data: ByteArray): ParsedActivity = parse(decode(filename, data), filename)
}
