package app.fitness.android

import android.app.Application
import app.fitness.android.ui.Prefs
import app.fitness.data.ActivityRepository
import app.fitness.data.FitnessDatabase
import app.fitness.data.ImportRunner

/** Hand-wired dependencies: one database, one repository, one import runner, for the life of the process. */
class FitnessApp : Application() {
    val prefs by lazy { Prefs(this) }
    val repository by lazy { ActivityRepository(FitnessDatabase.create(this), filesDir) }
    val importRunner by lazy { ImportRunner(repository, contentResolver) }
}
