package app.fitness.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** How the import treats a file that looks like a run already stored (PC: `--match`). */
enum class MatchMode {
    /** Skip the same run from two sources (same moment, or a whole number of hours apart). The default. */
    MOMENT,
    /** Also skip anything on the same local day with a similar distance. Wrongly drops real separate runs. */
    DAY,
    /** Only byte-identical files are duplicates. */
    OFF,
}

enum class SimilarKind { MOMENT, ZONE, DAY }

/** A stored activity to compare a new file with. */
class SimilarCandidate(val fileHash: String, val start: Instant, val distance: Double)

class SimilarMatch(val fileHash: String, val kind: SimilarKind, val offsetSeconds: Double, val existingStart: Instant)

/**
 * Spot an activity that is already stored under a different file (PC: `importers/similar.py`). Two files of one run (say
 * Nike Run Club and Strava) hash differently, so the exact-duplicate check misses them. Two activities are similar when
 * they are the same sport with a similar distance and their starts relate as: `moment` (within two minutes: the same
 * run), `zone` (a whole number of hours apart within a minute and within 5% distance: one source labelled local time as
 * UTC) or `day` (same local date only: not enough by itself).
 */
object Similar {
    const val SIMILAR_DISTANCE = 0.10                 // relative difference in distance
    const val ZONE_SIMILAR_DISTANCE = 0.05            // a timezone slip is weaker time evidence, so demand a closer distance
    const val SAME_MOMENT_SECONDS = 120.0
    const val ZONE_TOLERANCE_SECONDS = 60.0           // how far from a whole number of hours still counts as a zone slip
    const val MAX_ZONE_HOURS = 14                     // the largest UTC offset anywhere

    /** How two start instants relate, or null. Local dates use [zone], the zone the rest of the app buckets days in. */
    fun classify(newStart: Instant, oldStart: Instant, zone: ZoneId = ZoneId.systemDefault()): SimilarKind? {
        val delta = Times.seconds(oldStart, newStart)
        if (abs(delta) <= SAME_MOMENT_SECONDS) return SimilarKind.MOMENT
        val hours = Math.rint(delta / 3600).toInt()          // half to even, like Python's round
        if (hours != 0 && abs(hours) <= MAX_ZONE_HOURS && abs(delta - hours * 3600) <= ZONE_TOLERANCE_SECONDS) return SimilarKind.ZONE
        if (newStart.atZone(zone).toLocalDate() == oldStart.atZone(zone).toLocalDate()) return SimilarKind.DAY
        return null
    }

    fun similarDistance(a: Double?, b: Double?, tolerance: Double = SIMILAR_DISTANCE): Boolean =
        a != null && b != null && a != 0.0 && b != 0.0 && abs(a - b) <= tolerance * max(a, b)

    /** The UTC dates (`yyyy-MM-dd`) two days either side of [start], the window of stored activities worth comparing. */
    fun window(start: Instant): Pair<String, String> {
        val d = start.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return d.minusDays(2).toString() to d.plusDays(2).toString()
    }

    /** The best match, preferring the strongest relation and then the closest start. A file with no distance has none. */
    fun find(start: Instant, distance: Double?, candidates: List<SimilarCandidate>, zone: ZoneId = ZoneId.systemDefault()): SimilarMatch? {
        if (distance == null || distance == 0.0) return null
        var best: SimilarMatch? = null
        var bestRank = Pair(Int.MAX_VALUE, Double.MAX_VALUE)
        for (c in candidates) {
            if (!similarDistance(distance, c.distance)) continue
            val kind = classify(start, c.start, zone) ?: continue
            if (kind == SimilarKind.ZONE && !similarDistance(distance, c.distance, ZONE_SIMILAR_DISTANCE)) continue
            val offset = Times.seconds(c.start, start)
            val rank = Pair(kind.ordinal, abs(offset))
            if (rank.first < bestRank.first || (rank.first == bestRank.first && rank.second < bestRank.second)) {
                best = SimilarMatch(c.fileHash, kind, offset, c.start)
                bestRank = rank
            }
        }
        return best
    }

    /** Whether a match of this kind means "do not import". */
    fun skips(kind: SimilarKind, mode: MatchMode): Boolean = when (mode) {
        MatchMode.OFF -> false
        MatchMode.DAY -> true
        MatchMode.MOMENT -> kind == SimilarKind.MOMENT || kind == SimilarKind.ZONE
    }
}
