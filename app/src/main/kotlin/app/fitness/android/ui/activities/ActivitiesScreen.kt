package app.fitness.android.ui.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.DateRangePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.Labels
import app.fitness.android.ui.components.FilterPill
import app.fitness.android.ui.components.PrimaryButton
import app.fitness.android.ui.components.QuietButton
import app.fitness.android.ui.components.RouteThumbnail
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.Formatter
import app.fitness.data.ActivityListItem
import app.fitness.data.ImportState
import app.fitness.data.SportCount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun ActivitiesScreen(
    vm: ActivitiesViewModel,
    importState: ImportState,
    fmt: Formatter,
    onImport: () -> Unit,
    onDismissImport: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val t = Fitness.theme
    val items by vm.items.collectAsStateWithLifecycle()
    val sports by vm.sports.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val sport by vm.sport.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(t.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selection.isEmpty()) Header("Activities", total) else SelectionBar(
                count = selection.size, onCancel = vm::clearSelection, onAll = vm::selectAll, onDelete = { confirmDelete = true })
            FilterRow(range, sport, sports, onRange = { vm.range.value = it }, onSport = { vm.sport.value = it })
            if (importState.running || importState.finished) ImportBanner(importState, onDismissImport)
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(hasAny = (total ?: 0) > 0, onImport = onImport, onShowAll = { vm.range.value = RangeFilter.All; vm.sport.value = null })
                else -> {
                    Summary(list, fmt)
                    LazyColumn(
                        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(if (t.layout == LayoutStyle.CARDS) 12.dp else 10.dp),
                    ) {
                        items(list, key = { it.fileHash }) { a ->
                            FeedCard(
                                a, fmt, selected = a.fileHash in selection,
                                onClick = { if (selection.isEmpty()) onOpen(a.fileHash) else vm.toggle(a.fileHash) },
                                onLongClick = { vm.toggle(a.fileHash) },
                            )
                        }
                    }
                }
            }
        }
        if (selection.isEmpty()) {
            PrimaryButton(
                if (t.layout == LayoutStyle.TERMINAL) "+ import" else "+  Import", onImport,
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (confirmDelete) DeleteDialog(
        count = selection.size,
        onConfirm = { confirmDelete = false; vm.deleteSelected() },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun Header(title: String, count: Int?) {
    val t = Fitness.theme
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            if (t.layout == LayoutStyle.TERMINAL) "$ $title" else title, color = t.text,
            fontSize = if (t.layout == LayoutStyle.GROUPED) 32.sp else 26.sp, fontWeight = t.numeralWeight, modifier = Modifier.weight(1f),
        )
        if (count != null) Text("$count total", color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onAll: () -> Unit, onDelete: () -> Unit) {
    val t = Fitness.theme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietButton("Cancel", onCancel)
        Text("$count selected", color = t.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        QuietButton("All", onAll)
        QuietButton("Delete", onDelete, danger = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    range: RangeFilter, sport: String?, sports: List<SportCount>, onRange: (RangeFilter) -> Unit, onSport: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var sportMenu by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilterPill(sport?.let(Labels::sportName) ?: "All sports", active = sport != null, onClick = { sportMenu = true })
            DropdownMenu(expanded = sportMenu, onDismissRequest = { sportMenu = false }) {
                DropdownMenuItem(text = { Text("All sports") }, onClick = { onSport(null); sportMenu = false })
                for (s in sports) DropdownMenuItem(
                    text = { Text("${Labels.sportName(s.sport)}  (${s.n})") }, onClick = { onSport(s.sport); sportMenu = false })
            }
        }
        for (p in RangeFilter.presets()) FilterPill(p.label, active = range == p, onClick = { onRange(p) })
        FilterPill(if (range is RangeFilter.Custom) range.label else "Custom…", active = range is RangeFilter.Custom, onClick = { picking = true })
    }
    if (picking) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                QuietButton("OK", {
                    val a = state.selectedStartDateMillis
                    val b = state.selectedEndDateMillis ?: a
                    if (a != null && b != null) onRange(RangeFilter.Custom(utcDay(a), utcDay(b)))
                    picking = false
                })
            },
            dismissButton = { QuietButton("Cancel", { picking = false }) },
        ) { DateRangePicker(state, Modifier.height(480.dp)) }
    }
}

/** The date picker reports midnight UTC of the chosen calendar day. */
private fun utcDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun Summary(list: List<ActivityListItem>, fmt: Formatter) {
    val t = Fitness.theme
    val dist = list.sumOf { it.distance ?: 0.0 }
    val time = list.sumOf { it.duration ?: 0.0 }
    Text(
        "${list.size} ${if (list.size == 1) "activity" else "activities"} · ${fmt.distance(dist)} · ${Formatter.hours(time)}", color = t.muted, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyState(hasAny: Boolean, onImport: () -> Unit, onShowAll: () -> Unit) {
    val t = Fitness.theme
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (hasAny) "Nothing in this range" else "No activities yet", color = t.text, fontSize = 20.sp, fontWeight = t.numeralWeight)
        Text(
            if (hasAny) "Try another date range or sport." else "Import .fit, .gpx or .tcx files (also gzipped), or share them to this app from another app.",
            color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        if (hasAny) PrimaryButton("Show all activities", onShowAll) else PrimaryButton("Import files", onImport)
    }
}

@Composable
private fun ImportBanner(s: ImportState, onDismiss: () -> Unit) {
    val t = Fitness.theme
    var showFailures by remember { mutableStateOf(false) }
    ThemedCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (s.running) {
            Text("Importing ${s.done} of ${s.total}…", color = t.text, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(t.divider)) {
                Box(Modifier.fillMaxWidth(if (s.total > 0) s.done.toFloat() / s.total else 0f).height(4.dp).background(t.accent))
            }
        } else {
            Text(
                "Imported ${s.imported}" + (if (s.duplicates > 0) ", ${s.duplicates} already there" else "") +
                    (if (s.failed.isNotEmpty()) ", ${s.failed.size} failed" else ""),
                color = t.text, fontWeight = FontWeight.SemiBold,
            )
            Row {
                if (s.failed.isNotEmpty()) QuietButton("Show failures", { showFailures = true })
                QuietButton("Dismiss", onDismiss)
            }
        }
    }
    if (showFailures) AlertDialog(
        onDismissRequest = { showFailures = false },
        confirmButton = { QuietButton("Close", { showFailures = false }) },
        title = { Text("Files that could not be imported") },
        text = { Column(Modifier.height(280.dp).horizontalScroll(rememberScrollState())) { s.failed.forEach { Text(it, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) } } },
    )
}

/** One activity in the feed, in the current theme's layout. */
@Composable
fun FeedCard(a: ActivityListItem, fmt: Formatter, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val t = Fitness.theme
    ThemedCard(selected = selected, onClick = onClick, onLongClick = onLongClick) {
        if (t.layout == LayoutStyle.TERMINAL) {
            Text("> ${Labels.terminalStamp(a.startTime)}  ${Labels.title(a)}", color = t.accent, fontSize = 14.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (a.hasDistance) {
                    Text("${fmt.distance(a.distance)} dist", color = t.text, fontSize = 13.sp)
                    Text("${fmt.paceOrSpeed(a.sport, a.avgSpeed)} ${fmt.paceOrSpeedLabel(a.sport).lowercase()}", color = t.text, fontSize = 13.sp)
                }
                Text("${Formatter.duration(a.duration)} time", color = t.text, fontSize = 13.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(t.surfaceAlt), contentAlignment = Alignment.Center) {
                    Text(Labels.glyph(a.sport), fontSize = 20.sp)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(Labels.title(a), color = t.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(Labels.dateTime(a.startTime), color = t.muted, fontSize = 13.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (a.hasDistance) {
                    FeedStat(fmt.distance(a.distance), "Distance")
                    FeedStat(fmt.paceOrSpeed(a.sport, a.avgSpeed), fmt.paceOrSpeedLabel(a.sport))
                }
                FeedStat(Formatter.duration(a.duration), "Time")
            }
            if (a.route.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(110.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(t.cornerRadius / 2)).background(t.surfaceAlt)) {
                    RouteThumbnail(a.route, t.chart.route, strokeWidth = 4f)
                }
            }
        }
    }
}

@Composable
private fun FeedStat(value: String, label: String) {
    val t = Fitness.theme
    Column {
        Text(value, color = t.text, fontSize = 19.sp, fontWeight = t.numeralWeight)
        Text(label, color = t.muted, fontSize = 12.sp)
    }
}

/** One activity needs a plain confirmation; several need the word DELETE typed, since there is no undo. */
@Composable
fun DeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val bulk = count > 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bulk) "Delete $count activities?" else "Delete this activity?") },
        text = {
            Column {
                Text("They are removed from the app. The original files stay in the app's archive.")
                if (bulk) {
                    Text("Type DELETE to confirm.", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    OutlinedTextField(typed, { typed = it }, singleLine = true)
                }
            }
        },
        confirmButton = {
            val t = Fitness.theme
            androidx.compose.material3.TextButton(onClick = onConfirm, enabled = !bulk || typed == "DELETE") {
                Text("Delete", color = if (!bulk || typed == "DELETE") t.danger else t.muted)
            }
        },
        dismissButton = { QuietButton("Cancel", onDismiss) },
    )
}
