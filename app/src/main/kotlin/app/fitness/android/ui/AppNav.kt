package app.fitness.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.fitness.android.FitnessApp
import app.fitness.android.FolderPreview
import app.fitness.android.ui.components.QuietButton
import app.fitness.android.ui.activities.ActivitiesScreen
import app.fitness.android.ui.activities.ActivitiesViewModel
import app.fitness.android.ui.detail.DetailScreen
import app.fitness.android.ui.detail.DetailViewModel
import app.fitness.android.ui.settings.SettingsScreen
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.Formatter

/** What the screens can ask the activity to do: open a system picker, or start a job once the user has confirmed. */
class ImportActions(
    val files: () -> Unit,
    val folder: () -> Unit,
    val startFolder: () -> Unit,
    val backup: () -> Unit,
    val restore: () -> Unit,
)

private object Routes {
    const val ACTIVITIES = "activities"
    const val SETTINGS = "settings"
    const val DETAIL = "activity/{hash}"
    fun detail(hash: String) = "activity/$hash"
}

/** The single activity's navigation: two top-level tabs and the activity detail. */
@Composable
fun AppNav(app: FitnessApp, actions: ImportActions, nav: NavHostController = rememberNavController()) {
    val t = Fitness.theme
    val units by app.prefs.units.collectAsStateWithLifecycle()
    val streetMap by app.prefs.streetMap.collectAsStateWithLifecycle()
    val importState by app.importRunner.state.collectAsStateWithLifecycle()
    val folderPreview by app.folderPreview.collectAsStateWithLifecycle()
    val fmt = remember(units) { Formatter(units) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    Column(Modifier.fillMaxSize().background(t.background)) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = Routes.ACTIVITIES) {
                composable(Routes.ACTIVITIES) {
                    val vm: ActivitiesViewModel = viewModel(factory = viewModelFactory { initializer { ActivitiesViewModel(app.repository) } })
                    ActivitiesScreen(vm, importState, fmt, actions, app.importRunner::dismiss, app::cancelJob) { nav.navigate(Routes.detail(it)) }
                }
                composable(Routes.DETAIL) { e ->
                    val hash = e.arguments?.getString("hash").orEmpty()
                    val vm: DetailViewModel = viewModel(key = hash, factory = viewModelFactory { initializer { DetailViewModel(app.repository, hash) } })
                    DetailScreen(vm, fmt, streetMap) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS) {
                    val count by app.repository.count().collectAsStateWithLifecycle(initialValue = null)
                    SettingsScreen(app, actions, count)
                }
            }
        }
        folderPreview?.let { FolderPreviewDialog(it, onStart = actions.startFolder, onDismiss = app::dismissFolderPreview) }
        if (route == Routes.ACTIVITIES || route == Routes.SETTINGS) {
            BottomBar(route) { dest ->
                nav.navigate(dest) {
                    popUpTo(Routes.ACTIVITIES) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
}

/** What was found in the chosen folder, shown before anything is imported. */
@Composable
private fun FolderPreviewDialog(preview: FolderPreview, onStart: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (preview) { FolderPreview.Scanning -> "Looking at the folder…"; is FolderPreview.Ready -> "Import this folder?"; is FolderPreview.Failed -> "Could not read the folder" }) },
        text = {
            when (preview) {
                FolderPreview.Scanning -> Text("Listing activity files, including subfolders.")
                is FolderPreview.Failed -> Text(preview.reason)
                is FolderPreview.Ready -> {
                    val s = preview.scan
                    Column {
                        if (s.files.isEmpty()) Text("No .fit, .gpx or .tcx files found (gzipped ones count too).")
                        else if (s.newFiles == 0) Text("Nothing new: all ${s.files.size} activity files were handled by an earlier import.")
                        else {
                            Text("Found ${s.files.size} activity files: " + s.byFormat.entries.joinToString(", ") { "${it.value} ${it.key.uppercase()}" } + ".")
                            if (s.seenBefore > 0) Text("${s.seenBefore} were handled by an earlier import and will be skipped quickly.", modifier = Modifier.padding(top = 8.dp))
                            Text(
                                "It runs in the background with a notification, can be stopped at any time, and carries on where it left off if the phone interrupts it. Files already in the app are skipped.",
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { if (preview is FolderPreview.Ready && preview.scan.newFiles > 0) QuietButton("Import ${preview.scan.newFiles} files", onStart) },
        dismissButton = { QuietButton(if (preview is FolderPreview.Ready) "Cancel" else "Close", onDismiss) },
    )
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    val t = Fitness.theme
    val items = listOf(Routes.ACTIVITIES to ("Activities" to "●"), Routes.SETTINGS to ("Settings" to "⚙"))
    Column(Modifier.background(t.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(0.dp).background(t.divider).padding(top = if (t.layout == LayoutStyle.GROUPED) 0.5.dp else 1.dp))
        Row(Modifier.fillMaxWidth()) {
            for ((dest, label) in items) {
                val on = current == dest
                Column(
                    Modifier.weight(1f).clickable { if (!on) onSelect(dest) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (t.layout != LayoutStyle.TERMINAL) Text(label.second, color = if (on) t.accent else t.muted, fontSize = 18.sp)
                    Text(
                        if (t.layout == LayoutStyle.TERMINAL) (if (on) "[${label.first.lowercase()}]" else " ${label.first.lowercase()} ") else label.first,
                        color = if (on) t.accent else t.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
