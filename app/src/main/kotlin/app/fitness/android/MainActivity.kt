package app.fitness.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fitness.android.ui.AppNav
import app.fitness.android.ui.ImportActions
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
                val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    app.importRunner.start(uris)
                }
                val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    if (tree != null) {
                        // Keep read access to the folder, so the job can carry on after the app is closed or restarted.
                        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        app.previewFolder(tree)
                    }
                }
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    if (uri != null) app.exportBackup(uri)
                }
                val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) app.startRestore(uri)
                }
                val actions = ImportActions(
                    files = { files.launch(arrayOf("*/*")) },
                    folder = { folder.launch(null) },
                    startFolder = {
                        // The progress notification needs permission on Android 13+; the job runs without it if refused.
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        app.confirmFolder()
                    },
                    backup = { backup.launch("fitness-backup-${java.time.LocalDate.now()}.zip") },
                    restore = { restore.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
                AppNav(app, actions)
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
        app.importRunner.start(uris)
    }
}
