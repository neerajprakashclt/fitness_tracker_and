package app.fitness.data

import androidx.room.withTransaction
import app.fitness.core.Goal
import app.fitness.core.LogActivity
import app.fitness.core.LogMetric
import app.fitness.core.Period
import app.fitness.core.Times
import app.fitness.core.TrainingLoad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * The training log's data (PC: `log.py`): every activity as a row bucketed by local day, the goals, and the heart-rate
 * histograms. Flows re-emit when activities change, so the screens follow imports and deletes.
 */
class LogRepository(private val db: FitnessDatabase, private val repo: ActivityRepository) {
    private val dao = db.analytics()

    /** Every activity, oldest first. */
    fun rows(): Flow<List<LogActivity>> = dao.observeLogRows().map { list ->
        list.mapNotNull { r ->
            val start = Times.parse(r.startTime) ?: return@mapNotNull null
            LogActivity(r.fileHash, r.sport, TrainingLoad.localDate(start), r.duration, r.distance, r.elevationGain, r.load, r.elevationMissing)
        }
    }

    fun sports(): Flow<List<SportCount>> = repo.sports()

    /** Each activity's sport, local day and heart-rate histogram, oldest first. */
    fun zoneRows(): Flow<List<Triple<String, java.time.LocalDate, Map<Int, Double>>>> = dao.observeZoneRows().map { list ->
        list.mapNotNull { r ->
            val start = Times.parse(r.startTime) ?: return@mapNotNull null
            val hist = AnalyticsRepository.decodeHist(r.hrHist) ?: return@mapNotNull null
            Triple(r.sport, TrainingLoad.localDate(start), hist)
        }
    }

    fun goals(): Flow<List<Goal>> = dao.observeGoals().map { list ->
        list.mapNotNull { g ->
            val metric = LogMetric.entries.firstOrNull { it.name.equals(g.metric, true) } ?: return@mapNotNull null
            val period = Period.entries.firstOrNull { it.name.equals(g.period, true) } ?: return@mapNotNull null
            Goal(g.id, g.sport, metric, period, g.target)
        }
    }

    /** [target] is in the stored units: metres, seconds or a count. */
    suspend fun addGoal(sport: String?, metric: LogMetric, period: Period, target: Double) {
        require(metric.goalable && target > 0) { "Invalid goal" }
        val now = Times.format(Instant.now())
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            dao.putGoal(GoalEntity(id, sport, metric.name.lowercase(), period.name.lowercase(), target, now))
            repo.dao().log(ChangeEntity(entity = "goal", key = id, op = "put", changedAt = now))
        }
    }

    suspend fun deleteGoal(id: String) {
        val now = Times.format(Instant.now())
        db.withTransaction {
            dao.deleteGoal(id)
            repo.dao().log(ChangeEntity(entity = "goal", key = id, op = "delete", changedAt = now))
        }
    }
}
