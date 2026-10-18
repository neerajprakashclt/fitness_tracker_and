package app.fitness.android.ui

import android.content.Context
import app.fitness.android.ui.theme.Appearance
import app.fitness.core.Units
import app.fitness.data.UntypedSport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-device display preferences, kept in SharedPreferences and exposed as flows so a change applies live to every
 * screen. (Training settings such as FTP and max HR are data and go in the database in phase 2.)
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(sp.getString("theme", "strava")!!)
    val theme: StateFlow<String> = _theme

    private val _appearance = MutableStateFlow(
        runCatching { Appearance.valueOf(sp.getString("appearance", Appearance.SYSTEM.name)!!) }.getOrDefault(Appearance.SYSTEM))
    val appearance: StateFlow<Appearance> = _appearance

    private val _units = MutableStateFlow(
        runCatching { Units.valueOf(sp.getString("units", Units.METRIC.name)!!) }.getOrDefault(Units.METRIC))
    val units: StateFlow<Units> = _units

    private val _untyped = MutableStateFlow(
        runCatching { UntypedSport.valueOf(sp.getString("untyped", UntypedSport.UNKNOWN.name)!!) }.getOrDefault(UntypedSport.UNKNOWN))
    val untyped: StateFlow<UntypedSport> = _untyped

    /** Draw OpenStreetMap tiles under the route. Off by default: the app then makes no network requests at all. */
    private val _streetMap = MutableStateFlow(sp.getBoolean("street_map", false))
    val streetMap: StateFlow<Boolean> = _streetMap

    fun setTheme(id: String) { sp.edit().putString("theme", id).apply(); _theme.value = id }
    fun setAppearance(a: Appearance) { sp.edit().putString("appearance", a.name).apply(); _appearance.value = a }
    fun setUnits(u: Units) { sp.edit().putString("units", u.name).apply(); _units.value = u }
    fun setUntyped(u: UntypedSport) { sp.edit().putString("untyped", u.name).apply(); _untyped.value = u }
    fun setStreetMap(on: Boolean) { sp.edit().putBoolean("street_map", on).apply(); _streetMap.value = on }
}
