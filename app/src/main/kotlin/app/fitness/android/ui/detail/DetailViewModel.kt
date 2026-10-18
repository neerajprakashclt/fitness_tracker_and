package app.fitness.android.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fitness.core.ChartSeries
import app.fitness.core.Series
import app.fitness.data.ActivityDetail
import app.fitness.data.ActivityRepository
import app.fitness.data.AnalyticsRepository
import app.fitness.data.LoadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the detail screen draws from the per-second streams, prepared off the main thread. */
class StreamDigest(
    val time: DoubleArray,
    val lat: DoubleArray?,
    val lon: DoubleArray?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val heartRate: Series?,
    /** Speed in m/s; the screen converts to pace or km/h for the chosen units. */
    val speed: Series?,
    val altitude: Series?,
)

sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    class Ready(val detail: ActivityDetail, val streams: StreamDigest?, val load: LoadInfo? = null) : DetailState
}

class DetailViewModel(private val repo: ActivityRepository, private val analytics: AnalyticsRepository, private val hash: String) : ViewModel() {
    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state

    init {
        viewModelScope.launch {
            val detail = repo.detail(hash)
            if (detail == null) { _state.value = DetailState.NotFound; return@launch }
            val load = analytics.loadOf(hash)
            _state.value = DetailState.Ready(detail, null, load)    // show tiles, route and laps while the charts load
            val digest = withContext(Dispatchers.Default) { repo.streams(hash)?.let(::digest) }
            _state.value = DetailState.Ready(detail, digest, load)
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { repo.delete(listOf(hash)); onDone() }
    }

    private fun digest(s: Map<String, DoubleArray>): StreamDigest? {
        val time = s["time"] ?: return null
        val hr = s["heart_rate"]
        val hrValid = hr?.filter { !it.isNaN() && it > 0 }
        return StreamDigest(
            time = time,
            lat = s["lat"], lon = s["lon"],
            avgHeartRate = hrValid?.takeIf { it.isNotEmpty() }?.average(),
            maxHeartRate = hrValid?.maxOrNull(),
            heartRate = hr?.let { ChartSeries.bucketMean(time, it) { v -> v > 0 } },
            speed = s["speed"]?.let { ChartSeries.bucketMean(time, it) { v -> v >= 0.5 } },
            altitude = s["altitude"]?.let { ChartSeries.bucketMean(time, it) },
        )
    }
}
