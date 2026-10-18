package app.fitness.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.components.QuietButton
import app.fitness.android.ui.components.SectionHeader
import app.fitness.android.ui.components.ThemedCard
import app.fitness.android.ui.components.ThemedDivider
import app.fitness.android.ui.theme.Fitness
import app.fitness.core.Setting
import app.fitness.core.SettingSource
import app.fitness.core.TrainingLoad
import app.fitness.data.AnalyticsRepository
import kotlinx.coroutines.launch

private class Threshold(val key: String, val title: String, val unit: String, val range: ClosedFloatingPointRange<Double>, val pick: (app.fitness.core.EffectiveSettings) -> Setting)

private val thresholds = listOf(
    Threshold(TrainingLoad.KEY_FTP, "Functional threshold power", "W", 50.0..600.0) { it.ftp },
    Threshold(TrainingLoad.KEY_MAX_HR, "Maximum heart rate", "bpm", 100.0..230.0) { it.maxHr },
    Threshold(TrainingLoad.KEY_RESTING_HR, "Resting heart rate", "bpm", 25.0..120.0) { it.restingHr },
    Threshold(TrainingLoad.KEY_THRESHOLD_HR, "Threshold heart rate", "bpm", 80.0..220.0) { it.thresholdHr },
)

/** The four numbers the load score depends on. Unset ones are estimated from the data, as on the PC. */
@Composable
fun ThresholdsSection(analytics: AnalyticsRepository) {
    val t = Fitness.theme
    val scope = rememberCoroutineScope()
    val settings by remember(analytics) { analytics.settings() }.collectAsStateWithLifecycle(initialValue = null)
    var editing by remember { mutableStateOf<Threshold?>(null) }

    SectionHeader("Training thresholds")
    ThemedCard {
        thresholds.forEachIndexed { i, th ->
            if (i > 0) ThemedDivider()
            val s = settings?.let(th.pick)
            Row(Modifier.fillMaxWidth().clickable { editing = th }.padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(th.title, color = t.text, fontSize = 16.sp)
                    Text(sourceText(th, s), color = t.muted, fontSize = 12.sp)
                }
                Text(s?.value?.let { "%.0f %s".format(it, th.unit) } ?: "not set", color = t.accent, fontSize = 16.sp)
            }
        }
    }
    Text(
        "Training load uses these. A power file on a bike ride is scored from power and FTP; anything with heart rate from heart rate; the rest at a flat rate per hour. " +
            "Leave a value unset to use the estimate. Changing one recalculates your whole history.",
        color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
    )

    editing?.let { th ->
        val current = settings?.let(th.pick)
        var text by remember(th) { mutableStateOf(current?.takeIf { it.source == SettingSource.SET }?.value?.let { "%.0f".format(it) } ?: "") }
        val value = text.toDoubleOrNull()
        val valid = value != null && value in th.range
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(th.title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                        label = { Text(th.unit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = text.isNotEmpty() && !valid,
                    )
                    Text(
                        "Between %.0f and %.0f %s. %s".format(th.range.start, th.range.endInclusive, th.unit, sourceText(th, current).replaceFirstChar { it.uppercase() }),
                        color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { if (valid) QuietButton("Save", { scope.launch { analytics.saveSetting(th.key, value) }; editing = null }) },
            dismissButton = {
                Row {
                    if (current?.source == SettingSource.SET) QuietButton("Use estimate", { scope.launch { analytics.saveSetting(th.key, null) }; editing = null })
                    QuietButton("Cancel", { editing = null })
                }
            },
        )
    }
}

private fun sourceText(th: Threshold, s: Setting?): String = when (s?.source) {
    SettingSource.SET -> "set by you"
    SettingSource.ESTIMATED -> if (th.key == TrainingLoad.KEY_FTP) "estimated: 95% of your best 20-minute power" else "estimated from your data"
    SettingSource.DEFAULT -> "default; set it for better heart-rate load"
    null -> if (th.key == TrainingLoad.KEY_FTP) "needs a bike ride with power data" else "needs heart-rate data"
}
