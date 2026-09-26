package org.opencell.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.session.ConsoleEntry
import org.opencell.core.session.ConsoleKind

@Composable
fun ConsoleScreen(vm: MainViewModel) {
    val entries by vm.console.collectAsStateWithLifecycle()
    val state by vm.linkState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follow new entries while the user is at (or near) the bottom.
    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible < 0 || lastVisible >= entries.size - 3) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Console") },
                actions = { IconButton(onClick = vm::clearConsole) { Icon(Icons.Filled.Delete, "Clear log") } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "Nothing yet. UP writes, DOWN payloads and link events appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(entries, key = { it.id }) { ConsoleRow(it) }
            }
            HorizontalDivider()
            ConsoleInput(vm, enabled = state.isConnected)
        }
    }
}

@Composable
private fun ConsoleRow(e: ConsoleEntry) {
    val color = when (e.kind) {
        ConsoleKind.UP -> MaterialTheme.colorScheme.primary
        ConsoleKind.DOWN -> Color(0xFF2E7D32)
        ConsoleKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        ConsoleKind.ERROR -> MaterialTheme.colorScheme.error
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockTime(e.wallMillis), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(e.kind.name, style = MonoStyle, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(e.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (e.payload != null) {
            Text(e.hex!!, style = MonoStyle, modifier = Modifier.padding(start = 16.dp))
            Text("\"${e.ascii}\"", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun ConsoleInput(vm: MainViewModel, enabled: Boolean) {
    val draft = vm.consoleDraft()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !vm.hexMode,
                        onClick = { vm.hexMode = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Text") }
                    SegmentedButton(
                        selected = vm.hexMode,
                        onClick = { vm.hexMode = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Hex") }
                }
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = { vm.sendQuick("HELLO") }, label = { Text("HELLO") }, enabled = enabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.consoleInput,
                    onValueChange = { vm.consoleInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MonoStyle,
                    label = { Text(if (vm.hexMode) "Hex bytes (48 45 4c…)" else "Text (UTF-8)") },
                    supportingText = {
                        val e = draft.error
                        Text(
                            if (e != null && vm.consoleInput.isNotEmpty()) e else "${draft.size}/${MainViewModel.MAX} bytes",
                            color = if (e != null && vm.consoleInput.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    isError = draft.error != null && vm.consoleInput.isNotEmpty(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (enabled) vm.sendConsole() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = vm::sendConsole, enabled = enabled && draft.error == null) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.allowOversize, onCheckedChange = { vm.allowOversize = it })
                Spacer(Modifier.width(8.dp))
                Text(
                    "Allow over ${MainViewModel.MAX} bytes (to test the terminal's 0x0D)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
