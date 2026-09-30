package org.opencell.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.opencell.app.audio.CallAudio
import org.opencell.app.ui.theme.CallGreen
import org.opencell.app.ui.theme.HangupRed
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingProblem
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneSession
import org.opencell.core.voice.CodecId
import org.opencell.core.voice.VoiceState

/**
 * Outgoing, incoming, in-call and ended screens, shown full-screen over the app
 * whenever there is a call (and by CallActivity over the lock screen). A
 * connected call has voice ([audio]): mute, speaker, and a line saying what
 * the audio is doing.
 * [onClose] closes an ended call, or any call while the link is down
 * ([PhoneSession.dismissCall] drops it then; the next resync restores it if
 * it is still up in the terminal). When the link ended in a pairing failure
 * ([link]) it isn't reconnecting by itself, so [onRetry] is offered too.
 */
@Composable
fun CallScreen(
    session: PhoneSession,
    link: StateFlow<LinkState>,
    audio: CallAudio,
    onRetry: (LinkTarget) -> Unit,
    onClose: () -> Unit = session::dismissCall,
) {
    val phone by session.state.collectAsStateWithLifecycle()
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
            if (call.phase == CallPhase.CONNECTED && phone.linkUp) VoiceControls(audio)
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

/**
 * Mute, speaker, and what the call's audio is doing. RECORD_AUDIO is asked for
 * when a call first connects (voice spec §5.7), and again from "Allow microphone".
 */
@Composable
private fun VoiceControls(audio: CallAudio) {
    val voice by audio.voice.state.collectAsStateWithLifecycle()
    val speaker by audio.route.speaker.collectAsStateWithLifecycle()
    val route by audio.route.route.collectAsStateWithLifecycle()
    val granted by audio.micGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audio.refreshPermission(context)
    }
    LaunchedEffect(Unit) {
        if (!audio.micGranted.value) ask.launch(Manifest.permission.RECORD_AUDIO)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val v = voice) {
                VoiceState.Off -> Text("Starting audio…", style = MaterialTheme.typography.bodyMedium)
                is VoiceState.Unsupported -> Text(
                    "The network chose ${CodecId.label(v.codec)}, which this app can't play: no audio in this call.",
                    color = MaterialTheme.colorScheme.error,
                )
                is VoiceState.On -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (v.muted) {
                            Button(onClick = { audio.voice.setMuted(false) }) { Text("Unmute") }
                        } else {
                            OutlinedButton(onClick = { audio.voice.setMuted(true) }) { Text("Mute") }
                        }
                        if (speaker) {
                            Button(onClick = { audio.route.setSpeaker(false) }) { Text("Speaker off") }
                        } else {
                            OutlinedButton(onClick = { audio.route.setSpeaker(true) }) { Text("Speaker") }
                        }
                    }
                    when {
                        !granted -> {
                            Text(
                                "Microphone not allowed: the other side hears silence. If no question appears, " +
                                    "allow it in Settings > Apps > OpenCell > Permissions.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                        }
                        !v.mic -> Text(
                            "Microphone off: it starts when OpenCell is open on the screen.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        v.muted -> Text("Muted: the other side hears silence.")
                    }
                    Text(
                        CodecId.label(v.codec) + " · " + (route?.label ?: if (v.output) "no audio device" else "no audio output"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val s = v.stats
                    Text(
                        "Sent ${s.sent} · not sent ${s.dropped + s.late + s.failed} · received ${s.received} · " +
                            "concealed ${s.jitter.concealed}",
                        style = MonoStyle,
                    )
                }
            }
        }
    }
}
