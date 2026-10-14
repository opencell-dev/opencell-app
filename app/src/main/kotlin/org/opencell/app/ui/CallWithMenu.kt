package org.opencell.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opencell.core.voice.CodecInfo
import org.opencell.core.voice.CodecPreference

/**
 * A call button's long press (codec-negotiation spec §7.2): "Call with…" Automatic,
 * then each codec this build runs (experimental ones labelled), for this one call.
 * The setting doesn't change.
 */
@Composable
fun CallWithMenu(expanded: Boolean, codecs: List<CodecInfo>, onDismiss: () -> Unit, onPick: (CodecPreference) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            CALL_WITH,
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenuItem(text = { Text("Automatic") }, onClick = { onDismiss(); onPick(CodecPreference.Automatic) })
        codecs.forEach { c ->
            DropdownMenuItem(text = { Text(c.choiceLabel) }, onClick = { onDismiss(); onPick(CodecPreference.Only(c.id)) })
        }
    }
}

/** The menu's heading, and the long press's TalkBack label. */
const val CALL_WITH = "Call with…"
