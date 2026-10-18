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
import app.fitness.android.ui.activities.ActivitiesScreen
import app.fitness.android.ui.activities.ActivitiesViewModel
import app.fitness.android.ui.detail.DetailScreen
import app.fitness.android.ui.detail.DetailViewModel
import app.fitness.android.ui.settings.SettingsScreen
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.Formatter

private object Routes {
    const val ACTIVITIES = "activities"
    const val SETTINGS = "settings"
    const val DETAIL = "activity/{hash}"
    fun detail(hash: String) = "activity/$hash"
}

/** The single activity's navigation: two top-level tabs and the activity detail. */
@Composable
fun AppNav(app: FitnessApp, onImport: () -> Unit, nav: NavHostController = rememberNavController()) {
    val t = Fitness.theme
    val units by app.prefs.units.collectAsStateWithLifecycle()
    val streetMap by app.prefs.streetMap.collectAsStateWithLifecycle()
    val importState by app.importRunner.state.collectAsStateWithLifecycle()
    val fmt = remember(units) { Formatter(units) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    Column(Modifier.fillMaxSize().background(t.background)) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = Routes.ACTIVITIES) {
                composable(Routes.ACTIVITIES) {
                    val vm: ActivitiesViewModel = viewModel(factory = viewModelFactory { initializer { ActivitiesViewModel(app.repository) } })
                    ActivitiesScreen(vm, importState, fmt, onImport, app.importRunner::dismiss) { nav.navigate(Routes.detail(it)) }
                }
                composable(Routes.DETAIL) { e ->
                    val hash = e.arguments?.getString("hash").orEmpty()
                    val vm: DetailViewModel = viewModel(key = hash, factory = viewModelFactory { initializer { DetailViewModel(app.repository, hash) } })
                    DetailScreen(vm, fmt, streetMap) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS) {
                    val count by app.repository.count().collectAsStateWithLifecycle(initialValue = null)
                    SettingsScreen(app.prefs, count)
                }
            }
        }
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
