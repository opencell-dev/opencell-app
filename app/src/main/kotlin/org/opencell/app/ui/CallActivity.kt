package org.opencell.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencell.app.graph
import org.opencell.app.service.CallNotifier
import org.opencell.app.ui.theme.OpenCellTheme

/**
 * The call screen on its own, for the incoming-call notification: its
 * full-screen intent opens this over the lock screen (showWhenLocked and
 * turnScreenOn in the manifest), and its Answer button opens it with
 * [ACTION_ANSWER]. It closes when there is no call left to show.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val phone = graph.session.phone
        setContent {
            OpenCellTheme {
                val state by phone.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.call == null) { if (state.call == null) finish() }
                CallScreen(phone, onClose = { phone.dismissCall(); finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            graph.session.phone.answer()
            CallNotifier(this).cancel()
        }
    }

    companion object {
        const val ACTION_ANSWER = "org.opencell.app.ANSWER"
    }
}
