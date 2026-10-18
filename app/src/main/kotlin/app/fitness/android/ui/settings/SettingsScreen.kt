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
import app.fitness.android.ui.Prefs
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
import app.fitness.data.UntypedSport

@Composable
fun SettingsScreen(prefs: Prefs, activityCount: Int?) {
    val t = Fitness.theme
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

        SectionHeader("Import")
        ThemedCard {
            SwitchRow(
                "Guess running or walking", "For files that record no activity type (some GPX exports): decide from average pace. A type in the file is never overridden.",
                untyped == UntypedSport.GUESS_FOOT,
            ) { prefs.setUntyped(if (it) UntypedSport.GUESS_FOOT else UntypedSport.UNKNOWN) }
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
