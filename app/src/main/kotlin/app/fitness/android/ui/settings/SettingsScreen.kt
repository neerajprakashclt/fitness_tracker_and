package app.fitness.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.fitness.android.FitnessApp
import app.fitness.android.ui.ImportActions
import app.fitness.android.ui.components.QuietButton
import app.fitness.core.MatchMode
import app.fitness.data.StorageUsage
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.SegmentedControl
import app.fitness.android.ui.components.SwitchRow
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Appearance
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.android.ui.theme.ThemeFamilies
import app.fitness.android.ui.theme.ThemeFamily
import app.fitness.core.Units
import app.fitness.core.UntypedSport

@Composable
fun SettingsScreen(app: FitnessApp, actions: ImportActions, activityCount: Int?) {
    val t = Fitness.theme
    val prefs = app.prefs
    val match by prefs.match.collectAsStateWithLifecycle()
    var confirmRebuild by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf<StorageUsage?>(null) }
    LaunchedEffect(activityCount) { storage = app.repository.storageUsage(app.databaseFile) }
    val themeId by prefs.theme.collectAsStateWithLifecycle()
    val appearance by prefs.appearance.collectAsStateWithLifecycle()
    val units by prefs.units.collectAsStateWithLifecycle()
    val untyped by prefs.untyped.collectAsStateWithLifecycle()
    val streetMap by prefs.streetMap.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(t.background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (t.layout == LayoutStyle.TERMINAL) "$ settings" else "Settings", color = t.text,
            fontSize = if (t.layout == LayoutStyle.GROUPED) 32.sp else 26.sp, fontWeight = t.numeralWeight,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        )

        SectionHeader("Appearance")
        ThemedCard {
            ThemeFamilies.forEachIndexed { i, family ->
                if (i > 0) ThemedDivider()
                ThemeRow(family, selected = family.id == themeId, appearance = appearance) { prefs.setTheme(family.id) }
            }
        }
        ThemedCard {
            Text("Light / dark", color = t.text, fontSize = 16.sp)
            Text(
                "Applies to themes that have both variants (Strava, iOS style, Solarized, Gruvbox). The others are always dark.",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            SegmentedControl(Appearance.entries, appearance, { it.label }, prefs::setAppearance)
        }

        SectionHeader("Units")
        ThemedCard {
            SegmentedControl(Units.entries, units, { if (it == Units.METRIC) "Metric (km)" else "Imperial (mi)" }, prefs::setUnits)
            Text("Display only. Everything is stored in metric.", color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        ThresholdsSection(app.analytics)

        SectionHeader("Import")
        ThemedCard {
            SwitchRow(
                "Guess running or walking", "For files that record no activity type (some GPX exports): decide from average pace. A type in the file is never overridden.",
                untyped == UntypedSport.GUESS_FOOT,
            ) { prefs.setUntyped(if (it) UntypedSport.GUESS_FOOT else UntypedSport.UNKNOWN) }
        }
        ThemedCard {
            Text("The same run from two apps", color = t.text, fontSize = 16.sp)
            Text(
                "A file whose run is already stored under another file (same sport and distance, same start, or a whole number of hours apart for a time-zone slip) is skipped. " +
                    "\"Also same day\" skips anything on the same day with a similar distance, which can drop real separate runs.",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            SegmentedControl(
                listOf(MatchMode.MOMENT, MatchMode.DAY, MatchMode.OFF), match,
                { when (it) { MatchMode.MOMENT -> "Skip"; MatchMode.DAY -> "Also same day"; MatchMode.OFF -> "Keep all" } }, prefs::setMatch,
            )
        }

        SectionHeader("Data")
        ThemedCard {
            ActionRow("Import a folder", "Everything in it, including subfolders, in the background.", actions.folder)
            ThemedDivider()
            ActionRow("Back up", "Save a zip of the original files. The database is rebuilt from them.", actions.backup)
            ThemedDivider()
            ActionRow("Restore a backup", "Adds what is missing; nothing is overwritten.", actions.restore)
            ThemedDivider()
            ActionRow("Rebuild from the original files", "Read every file again after a parser fix. Keeps your activities and what you deleted.") { confirmRebuild = true }
            ThemedDivider()
            ActionRow("Forget import history", "The next folder import looks at every file again, not only new ones.") { confirmForget = true }
        }
        storage?.let {
            Text(
                "Stored on this phone: ${mb(it.total)} (originals ${mb(it.archive)}, per-second data ${mb(it.streams)}, database ${mb(it.database)}).",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }

        SectionHeader("Map")
        ThemedCard {
            SwitchRow(
                "Street map under routes", "Loads OpenStreetMap tiles over the network. Off: routes are drawn on a plain background and the app makes no network requests.",
                streetMap, prefs::setStreetMap,
            )
        }

        SectionHeader("About")
        ThemedCard {
            Text("${activityCount ?: 0} activities on this phone", color = t.text, fontSize = 16.sp)
            Text(
                "Everything is stored in this app's private storage: no account, no server. Uninstalling the app deletes it.",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
    if (confirmRebuild) AlertDialog(
        onDismissRequest = { confirmRebuild = false },
        title = { Text("Rebuild from the original files?") },
        text = { Text("Every archived file is read again and its activity updated. Nothing you deleted comes back. It runs in the background.") },
        confirmButton = { QuietButton("Rebuild", { confirmRebuild = false; app.startRebuild() }) },
        dismissButton = { QuietButton("Cancel", { confirmRebuild = false }) },
    )
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget import history?") },
        text = { Text("Your activities are kept. The next folder import will open every file again; ones already in the app are skipped.") },
        confirmButton = { QuietButton("Forget", { confirmForget = false; app.forgetImportHistory() }) },
        dismissButton = { QuietButton("Cancel", { confirmForget = false }) },
    )
}

private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "%.0f MB".format(bytes / (1L shl 20).toDouble())

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val t = Fitness.theme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, color = t.accent, fontSize = 16.sp)
        Text(subtitle, color = t.muted, fontSize = 12.sp)
    }
}

@Composable
private fun ThemeRow(family: ThemeFamily, selected: Boolean, appearance: Appearance, onClick: () -> Unit) {
    val t = Fitness.theme
    val preview = family.resolve(appearance, t.dark)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape).border(1.dp, t.divider, CircleShape).background(preview.background).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (c in listOf(preview.surface, preview.accent, preview.text)) Box(Modifier.size(14.dp).clip(CircleShape).background(c))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(family.name, color = t.text, fontSize = 16.sp)
            Text(family.description, color = t.muted, fontSize = 12.sp)
        }
        Text(if (selected) (if (t.layout == LayoutStyle.TERMINAL) "[x]" else "✓") else if (t.layout == LayoutStyle.TERMINAL) "[ ]" else "", color = t.accent, fontSize = 18.sp)
    }
}
