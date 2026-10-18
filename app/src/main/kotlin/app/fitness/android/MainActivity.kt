package app.fitness.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.AppNav
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.FitnessThemeHost

/** The one activity. Hosts the Compose UI and receives files shared from other apps. */
class MainActivity : ComponentActivity() {
    private val app get() = application as FitnessApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val themeId by app.prefs.theme.collectAsStateWithLifecycle()
            val appearance by app.prefs.appearance.collectAsStateWithLifecycle()
            FitnessThemeHost(themeId, appearance) {
                val view = LocalView.current
                val dark = Fitness.theme.dark
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    app.importRunner.start(uris, app.prefs.untyped.value)
                }
                AppNav(app, onImport = { picker.launch(arrayOf("*/*")) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Files shared from another app (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) go straight into the import queue. */
    private fun handleShare(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        app.importRunner.start(uris, app.prefs.untyped.value)
    }
}
