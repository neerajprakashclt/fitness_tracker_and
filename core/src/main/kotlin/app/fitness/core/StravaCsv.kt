package app.fitness.core

/**
 * Elevation gain from a Strava export's `activities.csv` (PC: `importers/strava_csv.py`). Some watches (e.g. Amazfit) record no
 * altitude, but Strava adds its own from terrain data and lists it in this file. Only the total, in metres, not a profile.
 *
 * Rows are matched to activities by the CSV's `Filename` column ("activities/21540385706.fit.gz"), which names the file in the
 * export. That number is Strava's upload id, NOT the `Activity ID` column: the two only coincide for a few files.
 */
object StravaCsv {
    const val NAME = "activities.csv"
    const val SOURCE = "strava_csv"

    /** RFC 4180: quoted fields may hold commas, doubled quotes and line breaks. A BOM at the start is ignored. */
    internal fun rows(text: String): Sequence<List<String>> = sequence {
        val src = text.removePrefix("﻿")
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                quoted && c == '"' && src.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(field.toString()); field.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && src.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.setLength(0)
                    if (row.size > 1 || row[0].isNotEmpty()) yield(row)
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); yield(row) }
    }

    /**
     * {file name in lower case: metres of gain}. Rows with no file, and blank, zero or unreadable gains, are left out: indoor
     * activities carry 0, which means "no information" rather than "perfectly flat". Throws if the columns are missing.
     */
    fun elevationGains(text: String): Map<String, Double> {
        val it = rows(text).iterator()
        if (!it.hasNext()) return emptyMap()
        val header = it.next()
        // Strava repeats some headers (Distance, Elapsed Time), so take the first of each.
        val nameCol = header.indexOf("Filename")
        val gainCol = header.indexOf("Elevation Gain")
        require(nameCol >= 0 && gainCol >= 0) { "This has no 'Filename' and 'Elevation Gain' columns. Is it Strava's activities.csv, in English?" }
        val out = HashMap<String, Double>()
        for (row in it) {
            val gain = row.getOrNull(gainCol)?.trim()?.toDoubleOrNull() ?: continue
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (gain > 0 && name.isNotEmpty()) out[name.substringAfterLast('/').lowercase()] = gain
        }
        return out
    }

    /**
     * The uploaded file's name, from an archived path like `imports/ab12cd34ef56_123.fit.gz`. A file that was itself an archive
     * copy (it already starts with such a prefix, as the PC's do) has the prefix twice; all of them are dropped.
     */
    fun originalName(sourceFile: String): String? =
        Regex("^(?:[0-9a-f]{12}_)+(.+)").find(sourceFile.substringAfterLast('/'))?.groupValues?.get(1)?.lowercase()
}
