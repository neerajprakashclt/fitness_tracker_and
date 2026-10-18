package app.fitness.android.ui

import androidx.compose.ui.graphics.Color
import app.fitness.core.Formatter
import app.fitness.core.RunClass
import app.fitness.core.RunType

/** Names, colours and one-line reasons for run types, the same as the PC app's and the poster's. */
object RunTypeStyle {
    fun label(t: RunType) = when (t) {
        RunType.EASY -> "Easy"; RunType.STEADY -> "Steady"; RunType.TEMPO -> "Tempo"
        RunType.LONG -> "Long run"; RunType.INTERVALS -> "Intervals"; RunType.UNKNOWN -> "Unlabelled"
    }

    fun color(t: RunType) = when (t) {
        RunType.EASY -> Color(0xFF74C0FC); RunType.STEADY -> Color(0xFF51CF66); RunType.TEMPO -> Color(0xFFFCC419)
        RunType.LONG -> Color(0xFF9775FA); RunType.INTERVALS -> Color(0xFFFA5252); RunType.UNKNOWN -> Color(0xFFADB5BD)
    }

    /** The mix chart's order, bottom of the stack first. */
    val ORDER = listOf(RunType.EASY, RunType.STEADY, RunType.TEMPO, RunType.LONG, RunType.INTERVALS, RunType.UNKNOWN)

    /** A sentence saying why a run got its type, in the user's units. */
    fun why(c: RunClass, fmt: Formatter): String {
        fun pct(x: Double?) = "${Math.round((x ?: 0.0) * 100)}%"
        val by = if (c.basis == "hr") " (by heart rate)" else ""
        return when (c.type) {
            RunType.INTERVALS -> "${c.reps} hard reps of about ${Formatter.duration(c.repMedianSeconds)} at ${fmt.pace(c.repSpeed)}"
            RunType.LONG -> c.typicalDistance?.let { "${fmt.distance(c.distance)}, against a usual ${fmt.distance(it)}" }
                ?: "${fmt.distance(c.distance)} or ${Formatter.hours(c.duration)}"
            RunType.TEMPO -> "${pct(c.hardShare)} of the run at threshold pace or harder$by"
            RunType.EASY -> "${pct(c.easyShare)} of the run below threshold pace$by"
            RunType.STEADY -> "a mix: ${pct(c.hardShare)} at threshold or harder$by"
            RunType.UNKNOWN -> "no intensity data to judge it by"
        }
    }
}
