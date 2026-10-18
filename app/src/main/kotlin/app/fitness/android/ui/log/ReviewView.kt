package app.fitness.android.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitness.android.ui.Labels
import app.fitness.android.ui.components.FilterPill
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.StatTile
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.Consistency
import app.fitness.core.Formatter
import app.fitness.core.LogActivity
import app.fitness.core.RecordSeries
import app.fitness.core.YearInReview
import app.fitness.core.YearReview
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dayLabel = DateTimeFormatter.ofPattern("d MMM yyyy")

/** One calendar year at a glance, with how it compares with the year before, and the longer view of how steady the training has been. */
@Composable
fun ReviewView(rows: List<LogActivity>, sports: List<String>, records: List<RecordSeries>, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    var sport by rememberSaveable { mutableStateOf(if ("running" in sports) "running" else sports.firstOrNull()) }
    val mine = remember(rows, sport) { rows.filter { it.sport == sport } }
    val years = remember(mine) { YearInReview.years(mine) }
    var year by rememberSaveable(sport) { mutableStateOf<Int?>(null) }
    val shownYear = year?.takeIf { it in years } ?: years.firstOrNull()

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sports.forEach { FilterPill(Labels.sportName(it), sport == it, { sport = it }) }
    }
    if (shownYear == null) { Text("No activities for this sport.", color = t.muted, modifier = Modifier.padding(4.dp)); return }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        years.forEach { FilterPill("$it", it == shownYear, { year = it }) }
    }
    val review = remember(mine, shownYear, records, sport) { YearInReview.review(mine, shownYear, if (sport == "running") records else emptyList()) } ?: return
    ReviewCard(review, Labels.sportName(sport!!), fmt, onOpen)
    val consistency = remember(mine) { YearInReview.consistency(mine, java.time.LocalDate.now()) }
    if (consistency != null) {
        SectionHeader("Consistency")
        ConsistencyCard(consistency)
    }
}

private fun pct(x: Double?) = x?.let { "%+.0f%%".format(it * 100) }

@Composable
private fun ReviewCard(r: YearReview, sportName: String, fmt: Formatter, onOpen: (String) -> Unit) {
    val t = Fitness.theme
    val tot = r.totals
    ThemedCard {
        Text("${r.year} in review", color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sportName, color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile("Distance", fmt.distance(tot.distance), Modifier.weight(1f))
            StatTile("Time", Formatter.hours(tot.duration), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            StatTile("Activities", "${tot.runs}", Modifier.weight(1f))
            StatTile(if (tot.elevationMissing > 0) "Elevation (incomplete)" else "Elevation", fmt.elevation(tot.elevation), Modifier.weight(1f))
        }
        if (tot.avgSpeed != null) Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            StatTile("Average ${fmt.paceOrSpeedLabel(sportKey(sportName)).lowercase()}", fmt.paceOrSpeed(sportKey(sportName), tot.avgSpeed), Modifier.weight(1f))
            StatTile("Active weeks", "${r.activeWeeks}", Modifier.weight(1f))
        }
        if (r.previous != null && r.change.isNotEmpty()) {
            ThemedDivider(Modifier.padding(vertical = 10.dp))
            Text("Compared with ${r.year - 1}", color = t.muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                r.change["distance"]?.let { Text("Distance ${pct(it)}", color = trend(it), fontSize = 14.sp, modifier = Modifier.weight(1f)) }
                r.change["duration"]?.let { Text("Time ${pct(it)}", color = trend(it), fontSize = 14.sp, modifier = Modifier.weight(1f)) }
                r.change["runs"]?.let { Text("Count ${pct(it)}", color = trend(it), fontSize = 14.sp, modifier = Modifier.weight(1f)) }
            }
        }
        if (tot.elevationMissing > 0) Text("${tot.elevationMissing} activities have a GPS track but no elevation, so their climb is not counted.", color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }

    SectionHeader("Highlights")
    ThemedCard {
        HighlightRow("Longest", "${fmt.distance(r.longestRun.distance)} on ${r.longestRun.date.format(dayLabel)}") { onOpen(r.longestRun.key) }
        ThemedDivider()
        HighlightRow("Biggest week", "${fmt.distance(r.biggestWeekDistance)}, week of ${r.biggestWeekStart.format(dayLabel)}")
        ThemedDivider()
        HighlightRow("Biggest month", "${fmt.distance(r.biggestMonthDistance)} in ${r.biggestMonth.getDisplayName(TextStyle.FULL, Locale.getDefault())}")
        ThemedDivider()
        HighlightRow("Favourite day", r.favouriteWeekday.getDisplayName(TextStyle.FULL, Locale.getDefault()) + "s")
    }

    SectionHeader("Personal bests set in ${r.year}")
    ThemedCard {
        if (r.personalBestsSet.isEmpty()) Text("No new personal bests this year.", color = t.muted, fontSize = 14.sp)
        r.personalBestsSet.forEachIndexed { i, (name, rec) ->
            if (i > 0) ThemedDivider()
            HighlightRow(name, "${Formatter.duration(rec.seconds)} on ${rec.date.format(dayLabel)}") { onOpen(rec.key) }
        }
    }
}

/** The review works on a sport's display name; the pace-or-speed choice needs the stored key. */
private fun sportKey(displayName: String) = when (displayName) { "Run" -> "running"; "Walk" -> "walking"; "Hike" -> "hiking"; else -> displayName.lowercase() }

@Composable
private fun trend(change: Double) = if (change >= 0) Fitness.theme.accent else Fitness.theme.muted

@Composable
private fun HighlightRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val t = Fitness.theme
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp)) {
        Text(label, color = t.muted, fontSize = 14.sp, modifier = Modifier.weight(0.8f))
        Text(value, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.6f))
    }
}

@Composable
private fun ConsistencyCard(c: Consistency) {
    val t = Fitness.theme
    ThemedCard {
        Row(Modifier.fillMaxWidth()) {
            StatTile("Current streak", "${c.currentStreakWeeks} weeks", Modifier.weight(1f))
            StatTile("Longest streak", "${c.longestStreakWeeks} weeks", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            StatTile("Per week lately", "%.1f".format(c.perWeekRecent), Modifier.weight(1f))
            StatTile("Active weeks, last 52", "${c.activeWeeksLast52}", Modifier.weight(1f))
        }
        Text(
            "A streak is a run of calendar weeks, Monday to Sunday, with at least one activity. The longest ran from ${c.longestStreakFrom?.format(dayLabel)} to ${c.longestStreakTo?.format(dayLabel)}. " +
                "The longest break was ${c.longestGapDays} days (${c.longestGapFrom?.format(dayLabel)} to ${c.longestGapTo?.format(dayLabel)}); the last activity was ${c.daysSinceLast} days ago.",
            color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}
