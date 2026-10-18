package app.fitness.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

object Times {
    /**
     * An instant in the form the PC app stores, `2026-08-27T12:08:03+00:00` (microseconds only when non-zero), so
     * stored times compare correctly as text and map straight onto the PC's database for a later sync.
     */
    fun format(t: Instant): String {
        val base = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").format(t.atOffset(ZoneOffset.UTC))
        val micros = t.nano / 1000
        return if (micros == 0) "$base+00:00" else "$base.${micros.toString().padStart(6, '0')}+00:00"
    }

    /** An ISO 8601 timestamp as an instant; a stamp with no offset is read as UTC. Null when it is not a timestamp. */
    fun parse(text: String?): Instant? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() }?.replace(' ', 'T') ?: return null
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Seconds from [from] to [to], to microsecond precision like Python's `total_seconds`. */
    fun seconds(from: Instant, to: Instant): Double {
        val micros = Math.addExact(
            Math.multiplyExact(to.epochSecond - from.epochSecond, 1_000_000L),
            ((to.nano - from.nano) / 1000).toLong(),
        )
        return micros / 1e6
    }
}
