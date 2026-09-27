package org.opencell.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingProblem
import org.opencell.core.link.PairingRules

/**
 * What the user must do about pairing (spec 2026-09-27-ble-pairing-design.md §4):
 * the code hint while Android's pairing dialog is up, Retry after a failed or
 * cancelled pairing, and the way to Bluetooth settings when the phone's bond
 * is stale (removing a bond is not a public API, so the user taps Forget).
 * Draws nothing in other link states.
 */
@Composable
fun PairingNotice(state: LinkState, onRetry: (LinkTarget) -> Unit, onBluetoothSettings: () -> Unit) {
    when (state) {
        is LinkState.Pairing -> PairingCard(
            "Pairing with ${state.target.label()}",
            PairingRules.HINT,
            // The terminal's own SMP timeout is 30 s (NimBLE): past that it ends the attempt itself.
            note = "You have 30 seconds to enter it, or the terminal ends the attempt and you'll need to try again.",
        )
        is LinkState.PairingFailed -> when (state.problem) {
            PairingProblem.FAILED -> PairingCard(
                "Pairing failed",
                "${state.reason.replaceFirstChar { it.uppercase() }}. Check the code on the terminal's Pairing " +
                    "screen and try again; after 3 wrong codes it refuses pairing for 60 s.",
            ) {
                Button(onClick = { onRetry(state.target) }) { Text("Retry") }
            }
            PairingProblem.STALE_BOND -> PairingCard(
                "The terminal forgot this phone",
                "Its pairings were cleared, but this phone still keeps the old one. In Bluetooth settings, " +
                    "open ${state.target.label()}, choose Forget, then tap Retry and enter the new code.",
            ) {
                Button(onClick = onBluetoothSettings) { Text("Bluetooth settings") }
                OutlinedButton(onClick = { onRetry(state.target) }) { Text("Retry") }
            }
        }
        else -> Unit
    }
}

private fun LinkTarget.label() = name ?: address

@Composable
private fun PairingCard(title: String, text: String, note: String? = null, actions: @Composable () -> Unit = {}) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
