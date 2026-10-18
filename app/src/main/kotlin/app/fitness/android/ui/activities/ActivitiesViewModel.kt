package app.fitness.android.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fitness.data.ActivityListItem
import app.fitness.data.ActivityRepository
import app.fitness.data.SportCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The date window of the feed. Days are local days in the phone's zone; the database compares UTC text. */
sealed class RangeFilter(val label: String) {
    abstract fun bounds(today: LocalDate): Pair<LocalDate?, LocalDate?>      // first and last day, inclusive

    data object ThisMonth : RangeFilter("This month") {
        override fun bounds(today: LocalDate) = today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
    }
    data object LastMonth : RangeFilter("Last month") {
        override fun bounds(today: LocalDate) = today.minusMonths(1).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
    }
    data object Last30 : RangeFilter("Last 30 days") {
        override fun bounds(today: LocalDate) = today.minusDays(29) to today
    }
    data object ThisYear : RangeFilter("This year") {
        override fun bounds(today: LocalDate) = LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
    }
    data object All : RangeFilter("All time") {
        override fun bounds(today: LocalDate) = null to null
    }
    data class Custom(val from: LocalDate, val to: LocalDate) : RangeFilter("$from – $to") {
        override fun bounds(today: LocalDate) = from to to
    }

    /** UTC instants for the query: start of the first local day, start of the day after the last. */
    fun instants(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Pair<Instant?, Instant?> {
        val (a, b) = bounds(today)
        return a?.atStartOfDay(zone)?.toInstant() to b?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
    }

    companion object {
        /** A function, not a property: a property here would be built while the subclasses are still initialising. */
        fun presets(): List<RangeFilter> = listOf(ThisMonth, LastMonth, Last30, ThisYear, All)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ActivitiesViewModel(private val repo: ActivityRepository) : ViewModel() {
    val range = MutableStateFlow<RangeFilter>(RangeFilter.ThisMonth)
    val sport = MutableStateFlow<String?>(null)
    val selection = MutableStateFlow<Set<String>>(emptySet())

    /** null while the first query runs. */
    val items: StateFlow<List<ActivityListItem>?> = combine(range, sport) { r, s -> r to s }
        .flatMapLatest { (r, s) -> val (from, to) = r.instants(); repo.list(from, to, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sports: StateFlow<List<SportCount>> = repo.sports().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val totalCount: StateFlow<Int?> = repo.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun toggle(hash: String) = selection.update { if (hash in it) it - hash else it + hash }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAll() { selection.value = items.value.orEmpty().map { it.fileHash }.toSet() }

    fun deleteSelected() {
        val hashes = selection.value
        selection.value = emptySet()
        viewModelScope.launch { repo.delete(hashes) }
    }
}
