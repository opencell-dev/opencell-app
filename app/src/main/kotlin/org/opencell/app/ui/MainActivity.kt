package org.opencell.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.opencell.app.graph
import org.opencell.app.ui.theme.OpenCellTheme

class MainActivity : ComponentActivity() {
    // An explicit factory with this activity's Application: the default one can hand an
    // AndroidViewModel the first Application it saw (Robolectric makes one per test).
    private val viewModel: MainViewModel by viewModels { viewModelFactory { initializer { MainViewModel(application) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            OpenCellTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The missed-call notification's tap ([ACTION_SHOW_RECENTS]) opens the Phone tab on Recents. */
    internal fun handle(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_RECENTS) viewModel.showRecents()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
        graph.callAudio.refreshPermission(this)
    }

    override fun onStart() {
        super.onStart()
        graph.mainActivityInFront.value = true
    }

    override fun onStop() {
        super.onStop()
        graph.mainActivityInFront.value = false
    }

    companion object {
        const val ACTION_SHOW_RECENTS = "org.opencell.app.SHOW_RECENTS"
    }
}
