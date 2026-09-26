package org.opencell.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.opencell.app.ui.theme.OpenCellTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

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
    }
}
