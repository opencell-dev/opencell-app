package org.opencell.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.loopback.LoopbackStats
import org.opencell.core.loopback.ProbeOutcome
import org.opencell.core.loopback.ProbeResult

/**
 * The bench loopback test: N writes at a fixed interval, each echoed by a
 * test cell; latency from the accepted UP write to the DOWN notification.
 */
@Composable
fun LoopbackScreen(vm: MainViewModel) {
    val report by vm.loopback.collectAsStateWithLifecycle()
    val state by vm.linkState.collectAsStateWithLifecycle()
    val running = report?.running == true

    Scaffold(
        topBar = { TopAppBar(title = { Text("Loopback") }) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LoopbackForm(vm, running = running, connected = state.isConnected) }
            report?.let { r ->
                item {
                    val done = r.probes.size
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (r.running) "Running: probe $done of ${r.config.count}" else "Finished: $done probes",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LinearProgressIndicator(
                            progress = { done.toFloat() / r.config.count },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { StatsCard(r.stats) }
                items(r.probes.asReversed(), key = { it.seq }) { ProbeRow(it, r.stats.threshold) }
            }
        }
    }
}

@Composable
private fun LoopbackForm(vm: MainViewModel, running: Boolean, connected: Boolean) {
    val (cfg, problem) = vm.loopbackConfig()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.loopCount,
                    onValueChange = { vm.loopCount = it },
                    label = { Text("Count") },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.loopIntervalMs,
                    onValueChange = { vm.loopIntervalMs = it },
                    label = { Text("Interval (ms)") },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = vm.loopPayload,
                onValueChange = { vm.loopPayload = it },
                label = { Text("Payload (text)") },
                singleLine = true,
                enabled = !running,
                textStyle = MonoStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.loopTag, onCheckedChange = { vm.loopTag = it }, enabled = !running)
                Spacer(Modifier.width(8.dp))
                Text("Tag each probe with #seq (exact echo matching)", style = MaterialTheme.typography.bodySmall)
            }
            if (cfg != null && problem == null) {
                val probe = cfg.payloadFor(0)
                Text("First probe: \"${probe.decodeToString()}\" (${probe.size} B)", style = MonoStyle)
                Text(
                    "Needs the terminal in GRANTED (UP is refused with 0x80 without a grant). " +
                        "Outside a call that means a test cell (ocbench cell) that keeps it granted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (vm.loopError ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (running) {
                FilledTonalButton(onClick = vm::stopLoopback) { Text("Stop") }
            } else {
                Button(onClick = vm::startLoopback, enabled = connected && problem == null) { Text("Start") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsCard(s: LoopbackStats) {
    Card(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Stat("Sent", "${s.sent}")
            Stat("Echoed", "${s.received}")
            Stat("Lost", "${s.lost}" + if (s.sent > 0 && s.lost > 0) " (%.0f%%)".format(s.lossPercent) else "")
            if (s.pending > 0) Stat("Waiting", "${s.pending}")
            if (s.sendFailed > 0) Stat("Refused", "${s.sendFailed}")
            if (s.stray > 0) Stat("Stray", "${s.stray}")
            Stat("Mean", s.mean.ms())
            Stat("Median", s.median.ms())
            Stat("Min", s.min.ms())
            Stat("Max", s.max.ms())
            Stat(
                "≤ ${s.threshold.inWholeMilliseconds} ms",
                if (s.received == 0) "–" else "${s.withinThreshold}/${s.received}",
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.widthIn(min = 72.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProbeRow(p: ProbeResult, threshold: kotlin.time.Duration) {
    val (text, color) = when (val o = p.outcome) {
        is ProbeOutcome.Echoed -> o.latency.ms() to if (o.latency <= threshold) Color(0xFF2E7D32) else Color(0xFFEF6C00)
        ProbeOutcome.Pending -> "waiting" to MaterialTheme.colorScheme.onSurfaceVariant
        ProbeOutcome.Lost -> "lost" to MaterialTheme.colorScheme.error
        is ProbeOutcome.SendFailed -> o.reason to MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("#%-4d".format(p.seq), style = MonoStyle)
        Text(p.payload.decodeToString(), style = MonoStyle, modifier = Modifier.weight(1f))
        if (p.attempts > 1) {
            Text("×${p.attempts}  ", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MonoStyle, color = color)
    }
}
