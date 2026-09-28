package org.opencell.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencell.core.protocol.ChannelGrid
import org.opencell.core.protocol.ScanList
import org.opencell.core.protocol.ScanSource
import org.opencell.core.protocol.UserChannel

/**
 * The terminal's scan list (channel-list spec §9): where it is looking now, the
 * list in scan order with each entry's source, the user's own channels (up to
 * 4, picked from the 915 MHz grid), the search outside the list, and "forget
 * learned cells". Every change goes to the terminal at once (COMMAND SCAN).
 */
@Composable
fun ChannelsScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val state by vm.linkState.collectAsStateWithLifecycle()
    val list by vm.channels.list.collectAsStateWithLifecycle()
    val problem by vm.channels.problem.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.channels.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channels") },
                actions = {
                    IconButton(onClick = { vm.channels.refresh() }, enabled = state.isConnected) {
                        Icon(Icons.Filled.Refresh, "Refresh the scan list")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    status?.scanLabel ?: if (state.isConnected) "The terminal doesn't report its scan" else "Not connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            problem?.let { p -> item { Text(p, color = MaterialTheme.colorScheme.error) } }
            val l = list
            if (l == null) {
                item { Text("No scan list yet: connect a terminal on the Terminal tab.") }
                return@LazyColumn
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your channels: ${l.userEntries.size} of ${ScanList.MAX_USER}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { adding = true }, enabled = l.userEntries.size < ScanList.MAX_USER) {
                        Text("Add channel")
                    }
                }
            }
            item { Text("Scan order", style = MaterialTheme.typography.titleSmall) }
            items(l.entries, key = { "${it.freqHz}-${it.fixed}-${it.sourceCode}" }) { e ->
                ListItem(
                    headlineContent = { Text("${e.mhzLabel} MHz") },
                    supportingContent = { Text(e.detail) },
                    trailingContent = if (e.source == ScanSource.USER) {
                        {
                            IconButton(onClick = { vm.channels.removeUser(e.freqHz) }) {
                                Icon(Icons.Filled.Delete, "Remove ${e.mhzLabel} MHz")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            item { FallbackCard(l, onApply = { a, c -> vm.channels.setFallback(a, c) }) }
            item {
                OutlinedButton(onClick = { vm.channels.forgetLearned() }) { Text("Forget learned cells") }
            }
        }
    }
    if (adding) {
        AddChannelDialog(
            onPick = { hz, fixed ->
                vm.channels.addUser(UserChannel(hz, fixed))
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/** Search outside the list: after how many passes (or never), and how many grid channels a round. */
@Composable
private fun FallbackCard(l: ScanList, onApply: (Int, Int) -> Unit) {
    var after by remember(l) { mutableIntStateOf(l.fallbackAfter) }
    var chunk by remember(l) { mutableIntStateOf(l.fallbackChunk) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Search outside the list", style = MaterialTheme.typography.titleSmall)
            Stepper(
                label = if (after >= ScanList.NEVER) "Never" else "After $after pass${if (after == 1) "" else "es"}",
                onMinus = { after = (after - 1).coerceAtLeast(0) },
                onPlus = { after = (after + 1).coerceAtMost(ScanList.NEVER) },
            )
            Stepper(
                label = "$chunk channels a round",
                onMinus = { chunk = (chunk - 1).coerceAtLeast(1) },
                onPlus = { chunk = (chunk + 1).coerceAtMost(ChannelGrid.COUNT) },
            )
            TextButton(
                onClick = { onApply(after, chunk) },
                enabled = after != l.fallbackAfter || chunk != l.fallbackChunk,
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onMinus) { Text("−", Modifier.width(16.dp)) }
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onPlus) { Text("+", Modifier.width(16.dp)) }
    }
}

/** One of the 52 grid channels; "fixed sync" for a Part 97 cell that beacons on one channel. */
@Composable
private fun AddChannelDialog(onPick: (Long, Boolean) -> Unit, onDismiss: () -> Unit) {
    var fixed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a channel") },
        text = {
            Column {
                Row(Modifier.clickable { fixed = !fixed }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fixed, onCheckedChange = { fixed = it })
                    Text("Fixed sync (Part 97 cells only)")
                }
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag(CHANNEL_PICKER)) {
                    items(ChannelGrid.all) { hz ->
                        TextButton(onClick = { onPick(hz, fixed) }, Modifier.fillMaxWidth()) {
                            Text("${ChannelGrid.mhz(hz)} MHz")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Test tag of the add-channel dialog's list of grid channels. */
const val CHANNEL_PICKER = "channel-picker"
