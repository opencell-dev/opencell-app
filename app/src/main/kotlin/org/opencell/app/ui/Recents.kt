package org.opencell.app.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.calllog.CallKind
import org.opencell.core.calllog.CallLogDisplay
import org.opencell.core.calllog.CallLogEntry
import org.opencell.core.protocol.PhoneNumber

/** What a Recents row can do; the screen wires these to [MainViewModel]. */
class RecentsActions(
    /** Tap a row: its number onto the keypad, to check before calling. */
    val onPutOnKeypad: (String) -> Unit,
    /** The row's call button, or Call in its menu: call it now. */
    val onCall: (String) -> Unit,
    val onDelete: (Long) -> Unit,
    /** Recents is on screen with the app resumed: missed calls count as seen. */
    val onSeen: () -> Unit,
)

/**
 * Recents (dial-and-recents spec §5): the call log grouped by day, newest
 * first. A row's call button calls back at once; tapping the row puts its
 * number on the keypad instead (a soft confirmation, the same on both screens
 * of the Fold); a long press offers Call, Copy number and Delete. While this
 * is on screen and the app is resumed, missed calls count as seen.
 */
@Composable
fun Recents(
    entries: List<CallLogEntry>,
    unseenMissed: Int,
    canDial: Boolean,
    devUnlocked: Boolean,
    actions: RecentsActions,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(unseenMissed) {
        if (unseenMissed > 0) actions.onSeen()
        onPauseOrDispose { }
    }
    if (entries.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No calls yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Calls you make and receive show here. The list stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val days = CallLogDisplay.byDay(entries, zone, LocalDate.now(zone), locale)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    LazyColumn(modifier.fillMaxSize()) {
        days.forEach { day ->
            stickyHeader(key = "day-${day.label}") {
                Text(
                    day.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(day.entries, key = { it.id }) { e ->
                RecentRow(e, time.format(Instant.ofEpochMilli(e.startedAt).atZone(zone)), canDial, devUnlocked, actions)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(e: CallLogEntry, time: String, canDial: Boolean, devUnlocked: Boolean, actions: RecentsActions) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val number = e.number
    val title = CallLogDisplay.title(e)
    Box {
        // A row without a number has nothing to put on the keypad: only its long press (the menu), so
        // TalkBack doesn't offer a tap that does nothing.
        val press = if (number != null) {
            Modifier.combinedClickable(
                onClick = { actions.onPutOnKeypad(number) },
                onLongClick = { menu = true },
                onClickLabel = "Put on the keypad",
                onLongClickLabel = "More options",
            )
        } else {
            Modifier
                .pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) }
                .semantics { onLongClick(label = "More options") { menu = true; true } }
        }
        ListItem(
            modifier = press.semantics(mergeDescendants = true) { contentDescription = CallLogDisplay.spoken(e, time) },
            leadingContent = {
                Icon(
                    kindIcon(e.kind),
                    contentDescription = null,
                    tint = if (e.kind == CallKind.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Text(title, color = if (e.kind == CallKind.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            },
            supportingContent = {
                Column {
                    CallLogDisplay.subtitleNumber(e)?.let { Text(it) }
                    Text(CallLogDisplay.detail(e))
                    if (devUnlocked) CallLogDisplay.voiceLine(e)?.let { Text(it, style = MonoStyle) }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.bodySmall)
                    if (number != null) {
                        IconButton(onClick = { actions.onCall(number) }, enabled = canDial) {
                            Icon(Icons.Filled.Call, contentDescription = "Call $title")
                        }
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (number != null) {
                DropdownMenuItem(text = { Text("Call") }, enabled = canDial, onClick = { menu = false; actions.onCall(number) })
                DropdownMenuItem(
                    text = { Text("Copy number") },
                    onClick = {
                        menu = false
                        val shown = if (PhoneNumber.isValid(number)) PhoneNumber.display(number) else number
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Number", shown))) }
                    },
                )
            }
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; actions.onDelete(e.id) })
        }
    }
}

private fun kindIcon(kind: CallKind): ImageVector = when (kind) {
    CallKind.OUTGOING -> CallIcons.Outgoing
    CallKind.INCOMING -> CallIcons.Incoming
    CallKind.MISSED -> CallIcons.Missed
    CallKind.REJECTED -> CallIcons.Rejected
    CallKind.UNKNOWN -> Icons.Filled.Call
}

/** "1 missed call", "3 missed calls": the badge's TalkBack text. */
fun missedCallsLabel(n: Int): String = if (n == 1) "1 missed call" else "$n missed calls"
