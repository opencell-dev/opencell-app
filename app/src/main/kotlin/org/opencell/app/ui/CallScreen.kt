package org.opencell.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.opencell.app.ui.theme.CallGreen
import org.opencell.app.ui.theme.HangupRed
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingProblem
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallData
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneSession

/**
 * Outgoing, incoming, in-call and ended screens, shown full-screen over the app
 * whenever there is a call (and by CallActivity over the lock screen). Voice is
 * not in this step: a connected call offers the data-frame test instead.
 * [onClose] closes an ended call, or any call while the link is down
 * ([PhoneSession.dismissCall] drops it then; the next resync restores it if
 * it is still up in the terminal). When the link ended in a pairing failure
 * ([link]) it isn't reconnecting by itself, so [onRetry] is offered too.
 */
@Composable
fun CallScreen(
    session: PhoneSession,
    link: StateFlow<LinkState>,
    onRetry: (LinkTarget) -> Unit,
    onClose: () -> Unit = session::dismissCall,
) {
    val phone by session.state.collectAsStateWithLifecycle()
    val data by session.callData.collectAsStateWithLifecycle()
    val linkState by link.collectAsStateWithLifecycle()
    val call = phone.call ?: return
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                when (call.direction) {
                    Direction.OUTGOING -> "Outgoing call"
                    Direction.INCOMING -> "Incoming call"
                    null -> "Call"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                call.peer?.let { PhoneSession.peerLabel(it, phone.number) } ?: if (call.direction == Direction.INCOMING) "Unknown caller" else "Unknown number",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                if (call.phase == CallPhase.ENDED) call.endText else call.phase.label,
                style = MaterialTheme.typography.titleLarge,
                color = if (call.phase == CallPhase.ENDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            if (call.phase == CallPhase.CONNECTED) {
                call.connectedAt?.let { Text("Since ${clockTime(it)}", style = MaterialTheme.typography.bodyMedium) }
            }
            call.id?.let { Text("Call $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val linkLost = !phone.linkUp && call.phase != CallPhase.ENDED
            val pairingFailed = (linkState as? LinkState.PairingFailed)?.takeIf { linkLost }
            if (linkLost) {
                Text(
                    when (pairingFailed?.problem) {
                        null -> "The phone lost the terminal; reconnecting. The call goes on in the terminal. " +
                            "Close hides it here; if it's still up when the terminal is back, it shows again."
                        PairingProblem.FAILED -> "The terminal needs pairing again (${pairingFailed.reason}). " +
                            "The call goes on in the terminal. Retry, then enter the code on the terminal's " +
                            "Pairing screen; Close hides the call here."
                        PairingProblem.STALE_BOND -> "The terminal needs pairing again: it no longer knows this " +
                            "phone. Forget it in Bluetooth settings, then Retry. The call goes on in the terminal; " +
                            "Close hides it here."
                    },
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            // While the link is down the call's buttons can't reach the terminal: offer Close instead,
            // and Retry when only the user can bring the link back (pairing failed: no automatic retry).
            when {
                pairingFailed != null -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    OutlinedButton(onClick = onClose) { Text("Close") }
                    Button(onClick = { onRetry(pairingFailed.target) }) { Text("Retry") }
                }
                linkLost -> OutlinedButton(onClick = onClose) { Text("Close") }
                else -> CallButtons(call, session, onClose)
            }
            if (call.phase == CallPhase.CONNECTED) DataTest(data, onSend = { session.sendTestFrames() })
            phone.notice?.let { NoticeLine(it, onDismiss = session::clearNotice) }
        }
    }
}

@Composable
private fun CallButtons(call: Call, session: PhoneSession, onClose: () -> Unit) {
    when (call.phase) {
        CallPhase.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(onClick = { session.reject() }, colors = ButtonDefaults.buttonColors(containerColor = HangupRed)) { Text("Reject") }
            Button(
                onClick = { session.answer() },
                enabled = !call.answering,
                colors = ButtonDefaults.buttonColors(containerColor = CallGreen),
            ) { Text(if (call.answering) "Answering…" else "Answer") }
        }
        CallPhase.CALLING, CallPhase.RINGING, CallPhase.CONNECTED ->
            Button(onClick = { session.hangup() }, colors = ButtonDefaults.buttonColors(containerColor = HangupRed)) { Text("Hang up") }
        CallPhase.RELEASING -> CircularProgressIndicator(Modifier.size(32.dp))
        CallPhase.ENDED -> OutlinedButton(onClick = onClose) { Text("Close") }
    }
}

/** Like the laptop client's send/recv steps: test frames out, and whatever the far end sends back. */
@Composable
private fun DataTest(data: CallData, onSend: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Data test", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "No voice yet. Send test frames over the call; the network's test peer echoes them, another terminal receives them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSend) { Text("Send 5 test frames") }
            Text(
                "Sent ${data.sent} · received ${data.received} (test frames ${data.testFramesReceived})" +
                    if (data.failed > 0) " · ${data.failed} not sent" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            data.lastReceivedHex?.let { Text("Last: $it", style = MonoStyle) }
        }
    }
}
