package org.opencell.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * ⋮ > Developer options (Terminal tab). Locked: a numeric code field: a wrong
 * try clears the field and shows a neutral message, with no lockout (the
 * code lives in the public source: [org.opencell.core.dev.DeveloperAccess]).
 * Unlocked: an offer to lock again. Unlocks Console, Loopback, the demo
 * terminal and the call screen's voice stats line.
 *
 * Drawn in place rather than with [androidx.compose.material3.AlertDialog]: a
 * real platform `Dialog` is a second window, and under Robolectric a focused
 * `OutlinedTextField`'s blinking cursor in that second window keeps Compose
 * from ever reporting idle (`AppNotIdleException`) — the same field is fine
 * on Console and Loopback, which aren't in a dialog. A scrim plus a centered
 * `Surface` gets the same look without a second window.
 */
@Composable
fun DeveloperOptionsDialog(unlocked: Boolean, onUnlock: (String) -> Boolean, onLock: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp)
                // Swallows clicks so tapping the card itself doesn't dismiss it via the scrim behind it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            if (unlocked) UnlockedContent(onLock, onDismiss) else LockedContent(onUnlock, onDismiss)
        }
    }
}

@Composable
private fun UnlockedContent(onLock: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Developer options", style = MaterialTheme.typography.headlineSmall)
        Text("Console, Loopback, the demo terminal and the call screen's voice stats line are unlocked on this phone.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Close") }
            TextButton(onClick = { onLock(); onDismiss() }) { Text("Lock") }
        }
    }
}

@Composable
private fun LockedContent(onUnlock: (String) -> Boolean, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Developer options", style = MaterialTheme.typography.headlineSmall)
        Text("Enter the code to unlock Console, Loopback, the demo terminal and the voice stats line.")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; wrong = false },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            isError = wrong,
            supportingText = {
                if (wrong) Text("That's not it.", color = MaterialTheme.colorScheme.error)
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                if (onUnlock(code)) {
                    onDismiss()
                } else {
                    wrong = true
                    code = ""
                }
            }) { Text("Unlock") }
        }
    }
}
