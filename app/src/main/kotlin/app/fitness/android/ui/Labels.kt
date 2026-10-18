package app.fitness.android.ui

import app.fitness.data.ActivityListItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Names, titles and glyphs for display. Sport values stay as stored; only the text shown here is friendly. */
object Labels {
    private val nouns = mapOf(
        "running" to "Run", "walking" to "Walk", "hiking" to "Hike", "cycling" to "Ride", "swimming" to "Swim",
        "weighttraining" to "Weight Training", "weight_training" to "Weight Training", "fitness_equipment" to "Gym Workout",
        "floor_climbing" to "Climb", "stairstepper" to "Stair Stepper", "elliptical" to "Elliptical", "yoga" to "Yoga",
        "training" to "Workout", "workout" to "Workout", "unknown" to "Activity", "alpine_skiing" to "Ski",
    )

    private val glyphs = mapOf(
        "running" to "🏃", "walking" to "🚶", "hiking" to "🥾", "cycling" to "🚴", "swimming" to "🏊",
        "weighttraining" to "🏋", "weight_training" to "🏋", "yoga" to "🧘", "alpine_skiing" to "⛷",
    )

    fun sportName(sport: String): String = nouns[sport] ?: sport.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun glyph(sport: String): String = glyphs[sport] ?: "●"

    /** Strava-style automatic title: "Morning Run". */
    fun title(a: ActivityListItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val hour = a.startTime.atZone(zone).hour
        val part = when { hour < 5 -> "Night"; hour < 12 -> "Morning"; hour < 17 -> "Afternoon"; hour < 21 -> "Evening"; else -> "Night" }
        val noun = sportName(a.sport)
        return if (a.sport == "unknown") "$part Activity" else "$part $noun"
    }

    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val dateOnly = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val terminalStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun dateTime(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dateTime.format(t.atZone(zone))
    fun date(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dateOnly.format(t.atZone(zone))
    fun terminalStamp(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String = terminalStamp.format(t.atZone(zone))
}
