package org.opencell.app.ui

import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.opencell.app.ui.theme.CallGreen
import org.opencell.core.phone.DialHint
import org.opencell.core.phone.DialPad
import org.opencell.core.phone.ServiceNumbers
import org.opencell.core.protocol.PhoneNumber

/** What the keypad does; the screen wires these to [MainViewModel]. */
class KeypadActions(
    val onKey: (Char) -> Unit,
    val onPlus: () -> Unit,
    val onBackspace: () -> Unit,
    val onClear: () -> Unit,
    val onPaste: (String) -> Unit,
    val onCall: () -> Unit,
    val onTestNumber: (String) -> Unit,
)

/**
 * The dial screen (dial-and-recents spec §3): the number as typed, grouped;
 * the line saying what Call will dial; "Test numbers"; the 3 x 4 keypad; and
 * Call (enabled only for a number [DialPad.hint] accepts, while [canDial]) with
 * Delete beside it. Stateless: [input] and [hint] come from the caller. Keys
 * size themselves to the space (48-80 dp); a window too short for a column
 * (the cover screen in landscape) puts the number beside the keys.
 */
@Composable
fun Keypad(input: String, hint: DialHint, canDial: Boolean, notice: String?, actions: KeypadActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = maxWidth > maxHeight && maxHeight < 480.dp
        if (side) {
            val key = keySize(maxWidth / 2, maxHeight - CALL_ROW)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    NumberDisplay(input, actions)
                    HintLine(hint, canDial, notice)
                    TestNumbers(canDial, actions.onTestNumber)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    KeyGrid(key, actions)
                    CallRow(input, hint, canDial, actions)
                }
            }
        } else {
            val key = keySize(maxWidth, maxHeight - DISPLAY_AND_HINT - CALL_ROW)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NumberDisplay(input, actions)
                HintLine(hint, canDial, notice)
                TestNumbers(canDial, actions.onTestNumber)
                Spacer(Modifier.height(8.dp))
                KeyGrid(key, actions)
                CallRow(input, hint, canDial, actions)
            }
        }
    }
}

private val DISPLAY_AND_HINT = 152.dp
private val CALL_ROW = 96.dp
private val GAP = 12.dp

/** A key's diameter: three across [width], four down [height], 48 dp (the touch minimum) to 80 dp. */
private fun keySize(width: Dp, height: Dp): Dp =
    min((width - 32.dp - GAP * 2) / 3, (height - GAP * 3) / 4).coerceIn(48.dp, 80.dp)

/** The number, grouped as typed ([DialPad.format]) and shrinking to fit; a long press offers Paste and Copy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberDisplay(input: String, actions: KeypadActions) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Paste or copy the number")
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(NUMBER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (input.isEmpty()) {
            Text("Enter a number", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            BasicText(
                DialPad.format(input),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 40.sp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Paste") },
                onClick = {
                    menu = false
                    scope.launch {
                        val clip = clipboard.getClipEntry()?.clipData
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text != null) actions.onPaste(text)
                    }
                },
            )
            if (input.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        menu = false
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Number", input))) }
                    },
                )
            }
        }
    }
}

/** What Call will dial, why it can't, or a refusal ([notice]) from the last try. */
@Composable
private fun HintLine(hint: DialHint, canDial: Boolean, notice: String?) {
    val (text, error) = when {
        notice != null -> notice to true
        !canDial -> "Calls need the terminal registered." to false
        else -> hint.text to hint.error
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** The core test services ([ServiceNumbers]); choosing one calls it. */
@Composable
private fun TestNumbers(canDial: Boolean, onTestNumber: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = canDial) { Text("Test numbers") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ServiceNumbers.ALL.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.label)
                            Text(PhoneNumber.display(s.number), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        open = false
                        onTestNumber(s.number)
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyGrid(key: Dp, actions: KeypadActions) {
    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
        DialPad.KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEach { k ->
                    DialKey(k, key, onPress = { actions.onKey(k) }, onLongPress = if (k == '0') actions.onPlus else null)
                }
            }
        }
    }
}

/**
 * One key: the character, its letters under it, a haptic tick (the system's
 * touch feedback setting decides). TalkBack hears "5", "Star", "Pound"; 0
 * offers "Plus" as its long-press action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(key: Char, size: Dp, onPress: () -> Unit, onLongPress: (() -> Unit)?) {
    val view = LocalView.current
    val press = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPress()
    }
    val long = onLongPress?.let {
        {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            it()
        }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(onClick = press, onLongClick = long)
            .clearAndSetSemantics {
                contentDescription = DialPad.spoken(key)
                role = Role.Button
                onClick { press(); true }
                if (long != null) onLongClick(label = "Plus") { long(); true }
            },
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.toString(), style = MaterialTheme.typography.headlineMedium)
            val letters = DialPad.letters(key)
            if (letters.isNotEmpty() || key in "123456789") {
                Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Call in the middle, Delete (long press: clear) to its right, balanced by an empty slot on the left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(input: String, hint: DialHint, canDial: Boolean, actions: KeypadActions) {
    val view = LocalView.current
    Row(Modifier.height(CALL_ROW), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.size(56.dp))
        FilledIconButton(
            onClick = actions.onCall,
            enabled = canDial && hint.callable,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallGreen),
        ) {
            Icon(Icons.Filled.Call, contentDescription = "Call", modifier = Modifier.size(32.dp))
        }
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (input.isNotEmpty()) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                actions.onBackspace()
                            },
                            onLongClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                actions.onClear()
                            },
                            onClickLabel = "Delete",
                            onLongClickLabel = "Clear the number",
                        )
                        .semantics { contentDescription = "Delete" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CallIcons.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The number display's test tag (UI tests read the grouped number from it). */
const val NUMBER_TAG = "dial-number"
