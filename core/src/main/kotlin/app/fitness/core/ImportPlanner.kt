package app.fitness.core

import java.time.ZoneId

/** The sport stored for a file whose format records none: leave it "unknown", or guess running/walking from pace. */
enum class UntypedSport { UNKNOWN, GUESS_FOOT }

/** What the planner needs to know about what is already stored. */
interface ActivityLookup {
    suspend fun exists(fileHash: String): Boolean

    /** Stored activities with a distance whose UTC start date is within [fromDate]..[toDate] (`yyyy-MM-dd`, inclusive). */
    suspend fun candidates(sport: String, anySport: Boolean, fromDate: String, toDate: String): List<SimilarCandidate>
}

sealed interface ImportDecision {
    /** Byte-identical content (compressed or not) is already stored. */
    data class Duplicate(val fileHash: String) : ImportDecision

    /** The same run as a stored activity, from another source. Not imported. */
    data class SameRun(val match: SimilarMatch) : ImportDecision

    /** To be imported. [kept] is a same-day lookalike that was judged a separate run, for the report. */
    class Import(val fileHash: String, val activity: ParsedActivity, val sport: String, val kept: SimilarMatch?) : ImportDecision
}

/**
 * Decides what importing one file does, without writing anything. Port of `plan_import` in the PC's
 * `importers/__init__.py`: exact duplicate by content hash, then same-run detection, then the sport label.
 */
object ImportPlanner {
    suspend fun plan(
        filename: String,
        data: ByteArray,
        lookup: ActivityLookup,
        untyped: UntypedSport = UntypedSport.UNKNOWN,
        match: MatchMode = MatchMode.MOMENT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ImportDecision {
        val decoded = Importer.decode(filename, data)
        if (lookup.exists(decoded.fileHash)) return ImportDecision.Duplicate(decoded.fileHash)

        val parsed = Importer.parse(decoded, filename)
        val noType = parsed.sport == "unknown"
        var found: SimilarMatch? = null
        if (match != MatchMode.OFF && parsed.distance != null && parsed.distance != 0.0) {
            val (from, to) = Similar.window(parsed.startTime)
            found = Similar.find(parsed.startTime, parsed.distance, lookup.candidates(parsed.sport, noType, from, to), zone)
        }
        if (found != null && Similar.skips(found.kind, match)) return ImportDecision.SameRun(found)

        val sport = if (untyped == UntypedSport.GUESS_FOOT && noType) Common.inferFootSport(parsed.distance, parsed.duration) else parsed.sport
        return ImportDecision.Import(decoded.fileHash, parsed, sport, found)
    }
}
