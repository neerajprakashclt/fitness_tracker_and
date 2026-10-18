package app.fitness.android.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fitness.core.CurvePoint
import app.fitness.core.Curves
import app.fitness.core.DayLoad
import app.fitness.core.EfficiencySeries
import app.fitness.core.RouteReport
import app.fitness.data.AnalyticsRepository
import app.fitness.data.CriticalSpeedScreen
import app.fitness.data.RunTypeMixScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How much of the history the fitness chart shows. */
enum class LoadRange(val label: String, val days: Long?) {
    WEEKS_6("6w", 42), MONTHS_3("3m", 91), MONTHS_6("6m", 182), YEAR_1("1y", 365), ALL("All", null);

    fun from(today: LocalDate): LocalDate? = days?.let { today.minus(it, ChronoUnit.DAYS) }
}

class CurveSet(val sport: String, val curves: Map<Curves.Metric, List<CurvePoint>>) {
    val hasPower get() = curves.getValue(Curves.Metric.POWER).isNotEmpty()
    val hasSpeed get() = curves.getValue(Curves.Metric.SPEED).isNotEmpty()
}

/** What the Running tab shows: critical speed and predictions, the run-type mix, and efficiency. */
class RunningReport(val criticalSpeed: CriticalSpeedScreen, val mix: RunTypeMixScreen, val efficiency: EfficiencySeries)

class TrainingViewModel(private val analytics: AnalyticsRepository) : ViewModel() {
    val daily: StateFlow<List<DayLoad>> = analytics.daily().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _range = MutableStateFlow(LoadRange.MONTHS_6)
    val range: StateFlow<LoadRange> = _range
    fun setRange(r: LoadRange) { _range.value = r }

    private val _sports = MutableStateFlow<List<String>>(emptyList())
    val sports: StateFlow<List<String>> = _sports

    private val _curves = MutableStateFlow<CurveSet?>(null)
    val curves: StateFlow<CurveSet?> = _curves

    private val _running = MutableStateFlow<RunningReport?>(null)
    val running: StateFlow<RunningReport?> = _running

    private val _routes = MutableStateFlow<List<RouteReport>?>(null)
    val routes: StateFlow<List<RouteReport>?> = _routes

    private var efficiencySport: String? = null

    init {
        // Also reloads when the analytics job finishes after the screen was first opened.
        viewModelScope.launch {
            analytics.daily().collect {
                if (_sports.value.isEmpty()) {
                    _sports.value = analytics.curveSports()
                    _sports.value.firstOrNull()?.let { s -> select(s) }
                }
                if (it.isNotEmpty()) loadRunning()
            }
        }
    }

    fun select(sport: String) {
        viewModelScope.launch { _curves.value = CurveSet(sport, analytics.curves(sport)) }
    }

    fun selectEfficiencySport(sport: String) {
        efficiencySport = sport
        viewModelScope.launch { _running.value = _running.value?.let { RunningReport(it.criticalSpeed, it.mix, analytics.efficiency(sport)) } }
    }

    /** Called when the Routes tab is first shown: grouping every run's track takes a moment. */
    fun loadRoutes() {
        if (_routes.value != null) return
        viewModelScope.launch { _routes.value = analytics.routes() }
    }

    private fun loadRunning() {
        viewModelScope.launch {
            _running.value = RunningReport(analytics.criticalSpeedScreen(), analytics.runTypeMix(), analytics.efficiency(efficiencySport))
            if (_routes.value != null) _routes.value = analytics.routes()
        }
    }
}
