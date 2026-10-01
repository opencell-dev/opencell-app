package org.opencell.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencell.app.graph
import org.opencell.app.ui.theme.OpenCellTheme

/**
 * The call screen on its own, for the incoming-call notification: its
 * full-screen intent opens this over the lock screen (showWhenLocked and
 * turnScreenOn in the manifest), and its Answer button opens it with
 * [ACTION_ANSWER]. It follows [callEndAction] to close itself: right away
 * once there's no call left to show, or a few seconds after the call reaches
 * ENDED (so "Call ended" is visible for a moment rather than the screen
 * vanishing the instant it happens), dismissing the ended call as it goes;
 * a new call in those seconds cancels the close.
 *
 * Answering here is just another accepted command: this leaves the
 * incoming-call notification and the ringer to [org.opencell.app.service.LinkService]'s
 * state-driven collector, so a refused ANSWER (a race with the far end)
 * leaves both up rather than this activity hiding them itself.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val phone = graph.session.phone
        lifecycleScope.launch {
            // collectLatest: a new call (INCOMING) while waiting to close after ENDED cancels that close.
            phone.state.map { it.call?.phase }.distinctUntilChanged().collectLatest { phase ->
                when (val action = callEndAction(phase)) {
                    CallEndAction.FinishNow -> finish()
                    is CallEndAction.FinishAfter -> {
                        delay(action.delayMillis)
                        // Closing by itself counts as the user's Close: otherwise MainActivity would show the same "Call ended" again.
                        phone.dismissCall()
                        finish()
                    }
                    CallEndAction.Wait -> Unit
                }
            }
        }
        setContent {
            OpenCellTheme {
                val devUnlocked by graph.developerAccess.unlocked.collectAsStateWithLifecycle()
                CallScreen(
                    phone,
                    graph.session.link.state,
                    graph.callAudio,
                    devUnlocked,
                    onRetry = graph.repository::connect,
                    onClose = { phone.dismissCall(); finish() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.callActivityInFront.value = true
    }

    override fun onResume() {
        super.onResume()
        graph.callAudio.refreshPermission(this)
    }

    override fun onStop() {
        super.onStop()
        graph.callActivityInFront.value = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            graph.session.phone.answer()
        }
    }

    companion object {
        const val ACTION_ANSWER = "org.opencell.app.ANSWER"
    }
}
