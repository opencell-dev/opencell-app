package org.opencell.app.ui

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
        setContent {
            OpenCellTheme {
                AppRoot(viewModel)
            }
        }
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
}
