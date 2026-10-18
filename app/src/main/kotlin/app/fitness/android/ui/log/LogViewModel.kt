package app.fitness.android.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fitness.core.Goal
import app.fitness.core.LogActivity
import app.fitness.core.LogMetric
import app.fitness.core.Period
import app.fitness.core.RecordSeries
import app.fitness.core.WeeklyZones
import app.fitness.data.AnalyticsRepository
import app.fitness.data.LogRepository
import app.fitness.data.SportCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Log tab's data: every activity (re-read when activities change), the goals, and the sports to filter by. */
@OptIn(FlowPreview::class)
class LogViewModel(private val log: LogRepository, private val analytics: AnalyticsRepository) : ViewModel() {
    // An import writes many rows; wait for it to settle rather than redraw for each.
    val rows: StateFlow<List<LogActivity>?> = log.rows().debounce(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val goals: StateFlow<List<Goal>> = log.goals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sports: StateFlow<List<SportCount>> = log.sports().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Running personal bests, for the year in review; reloaded when the activities change. */
    private val _records = MutableStateFlow<List<RecordSeries>>(emptyList())
    val records: StateFlow<List<RecordSeries>> = _records

    init {
        viewModelScope.launch { log.rows().debounce(500).collect { _records.value = analytics.records() } }
    }

    fun addGoal(sport: String?, metric: LogMetric, period: Period, target: Double) {
        viewModelScope.launch { log.addGoal(sport, metric, period, target) }
    }

    fun deleteGoal(id: String) {
        viewModelScope.launch { log.deleteGoal(id) }
    }
}

/** Weekly heart-rate zones for the Zones view of the Training tab. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ZonesViewModel(log: LogRepository, analytics: AnalyticsRepository) : ViewModel() {
    private val sport = MutableStateFlow<String?>(null)
    private val weeks = MutableStateFlow(26)
    val sportFilter: StateFlow<String?> = sport
    val weeksShown: StateFlow<Int> = weeks

    fun setSport(s: String?) { sport.value = s }
    fun setWeeks(w: Int) { weeks.value = w }

    /** Null while loading; a [ZoneState.Empty] when there is no heart-rate data or no max heart rate. */
    val state: StateFlow<ZoneState?> = combine(log.zoneRows().debounce(300), analytics.settings(), sport) { rows, settings, s ->
        val maxHr = settings.maxHr.value
        val report = if (maxHr == null || maxHr == 0.0) null else app.fitness.core.WeeklyZoneReport.weekly(rows, maxHr, s)
        if (report == null) ZoneState.Empty else ZoneState.Ready(report, rows.map { it.first }.distinct())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

sealed interface ZoneState {
    data object Empty : ZoneState
    class Ready(val report: WeeklyZones, val sports: List<String>) : ZoneState
}
